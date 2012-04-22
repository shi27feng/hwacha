package hwacha

import Chisel._
import Node._
import Constants._
import Commands._
import Instructions._

class PCBundle extends Bundle
{
  val taken = Bits(width=SZ_ADDR)
  val not_taken = Bits(width=SZ_ADDR)
}

class ioPCPipe extends ioPipe()( new PCBundle() )

class ioIssueVTToPVFB extends Bundle
{
  val stop = Bool(OUTPUT)
  val pc = new ioPCPipe()
  val pvfb_tag = Bits(SZ_PVFB_TAG, OUTPUT)
}

class ReplayBundle extends Bundle
{
  val pc = Bits(width=SZ_ADDR)
  val tag = Bits(width=SZ_PVFB_TAG)
}

class ioIssueVTToPC extends Bundle
{
  val replay_pre_if = new ioPipe()( new ReplayBundle )
  val replay_if = new ioPipe()( new ReplayBundle )
  val replay_jump = new ioPipe()( new ReplayBundle )
  val replay_branch = new ioPipe()( new ReplayBundle )
  val replay_stop = new ioPipe()( new ReplayBundle )
}

class io_vf extends Bundle
{
  val active = Bool(OUTPUT)
  val fire = Bool(OUTPUT)
  val stop = Bool(INPUT)
  val pc = Bits(SZ_ADDR, OUTPUT)
  val nxregs = Bits(SZ_REGCNT, OUTPUT)
  val nfregs = Bits(SZ_REGCNT, OUTPUT)
  val imm1_rtag = Bits(SZ_AIW_IMM1, OUTPUT)
  val numCnt_rtag = Bits(SZ_AIW_NUMCNT, OUTPUT)
  val stride = Bits(SZ_REGLEN, OUTPUT)
  val vlen = Bits(SZ_VLEN, OUTPUT)
}

class io_issue_vt_to_irq_handler extends Bundle
{
  val ma_inst = Bool(OUTPUT)
  val fault_inst = Bool(OUTPUT)
  val illegal = Bool(OUTPUT)
  val pc_if = Bits(SZ_ADDR, OUTPUT)
  val pc_id = Bits(SZ_ADDR, OUTPUT)
}

class io_vxu_issue_vt extends Bundle
{
  val irq = new io_issue_vt_to_irq_handler()

  val imem_req = new io_imem_req()
  val imem_resp = new io_imem_resp().flip

  val vitlb_exception = Bool(INPUT)

  val vf = new io_vf().flip

  val laneToIssue = new ioLaneToIssue().flip()

  val valid = new io_vxu_issue_fire().asOutput
  val ready = Bool(INPUT)
  val dhazard = new io_vxu_issue_reg().asOutput
  val shazard = new io_vxu_issue_fu().asOutput
  val bhazard = new io_vxu_issue_op().asOutput
  val fn = new io_vxu_issue_fn().asOutput
  val decoded = new io_vxu_issue_regid_imm().asOutput

  val vxu_cntq = new io_vxu_cntq().flip()
  val aiw_cntb = new io_vxu_cntq()

  val issue_to_aiw = new io_issue_to_aiw()
  val aiw_to_issue = new io_aiw_to_issue().flip

  val flush = Bool(INPUT)
  val xcpt_to_issue = new io_xcpt_handler_to_issue().flip()
}

class vuVXU_Issue_VT extends Component
{
  val io = new io_vxu_issue_vt()

  val pcStage = new vuPCStage()

  io.vf.stop := pcStage.io.pcToTVEC.stop

  pcStage.io.flush := io.flush

  pcStage.io.pc.id := UFix(0)
  pcStage.io.pc.fire := io.vf.fire
  pcStage.io.pc.pc := io.vf.pc
  pcStage.io.pc.vlen := io.vf.vlen

  pcStage.io.laneToIssue <> io.laneToIssue

  val stall_sticky = Reg(resetVal = Bool(false))
  val stall = stall_sticky || io.irq.illegal || io.xcpt_to_issue.stall

  when (io.irq.ma_inst || io.irq.fault_inst || io.irq.illegal) { stall_sticky := Bool(true) }
  when (io.flush) { stall_sticky := Bool(false) }

  val mask_stall = Wire(){ Bool() }
  val stalld = !(io.ready && (mask_stall || io.aiw_cntb.ready) & !stall)
  val stallf = !io.imem_resp.valid || !io.imem_req.ready || io.irq.ma_inst || io.irq.fault_inst

  val flushd = Wire(){ Bool() }
  val tagd = Wire(){ Bits(width=SZ_PVFB_TAG) }

  val reg_flushd = Reg(flushd)
  val reg_tagd = Reg(tagd)

  val imm1_rtag = Reg(resetVal = Bits(0,SZ_AIW_IMM1))
  val numCnt_rtag = Reg(resetVal = Bits(0,SZ_AIW_CMD))

  val imem_sb = SkidBuffer(pcStage.io.pcToVT, true, flushable = true)

  val pre_if = imem_sb.io.deq
  val if_reg = imem_sb.io.pipereg
  val killf = imem_sb.io.kill || io.vf.fire

  imem_sb.io.nack := stallf || stalld
  imem_sb.io.deq.ready := Bool(true)
  imem_sb.io.flush := io.flush || flushd

  pcStage.io.vtToPC.replay_pre_if.valid := flushd && pre_if.bits.id != tagd
  pcStage.io.vtToPC.replay_pre_if.bits.pc := pre_if.bits.pc
  pcStage.io.vtToPC.replay_pre_if.bits.tag := pre_if.bits.id

  pcStage.io.vtToPC.replay_if.valid := flushd && if_reg.bits.id != tagd
  pcStage.io.vtToPC.replay_if.bits.pc := if_reg.bits.pc
  pcStage.io.vtToPC.replay_if.bits.tag := if_reg.bits.id

  when(io.flush) 
  {
    imm1_rtag := Bits(0,SZ_AIW_IMM1)
    numCnt_rtag := Bits(0,SZ_AIW_CMD)
  }
  .elsewhen (io.vf.fire) 
  { 
    imm1_rtag := io.vf.imm1_rtag
    numCnt_rtag := io.vf.numCnt_rtag
  }

  io.imem_req.bits := pre_if.bits.pc
  io.imem_req.valid := pre_if.valid

  val id_reg_pc = Reg(resetVal = Bits(0,SZ_ADDR))
  val id_reg_mask = Reg(resetVal = Bits(0,WIDTH_PVFB))
  val id_reg_tag = Reg(resetVal = Bits(0, SZ_PVFB_TAG))
  val id_reg_vlen = Reg(resetVal = Bits(0, SZ_VLEN))
  val id_reg_inst = Reg(resetVal = Bits(0,SZ_INST))
  val id_pc_next = Reg(resetVal = Bits(0,SZ_ADDR))

  io.decoded.aiw.imm1_rtag := imm1_rtag
  io.decoded.aiw.numCnt_rtag := numCnt_rtag
  io.decoded.aiw.cnt_rtag := io.aiw_to_issue.cnt_rtag
  io.decoded.aiw.pc_next := id_reg_pc + UFix(4, SZ_ADDR)
  io.decoded.aiw.update_imm1 := Bool(true)

  when (io.flush)
  {
    id_reg_pc := Bits(0,SZ_ADDR)
    id_reg_inst := Bits(0,SZ_INST)
    id_pc_next := Bits(0,SZ_ADDR)
  }
  .elsewhen (!stalld)
  {
    id_reg_pc := imem_sb.io.pipereg.bits.pc
    id_reg_mask := imem_sb.io.pipereg.bits.mask
    id_reg_tag := imem_sb.io.pipereg.bits.id
    id_reg_vlen := imem_sb.io.pipereg.bits.vlen
    id_reg_inst := io.imem_resp.bits
    id_pc_next := imem_sb.io.deq.bits.pc
    when (killf || stallf || flushd || reg_flushd)
    { 
      id_reg_inst := NOP
    }
  }

  val n = Bits(0,1)
  val y = Bits(1,1)

  val cs =
  ListLookup(id_reg_inst, 
                //         valid                                                    bhazard
                //         |                                                        |r2wm
                //         |viu                                  shazard            || r1w1                                                                                                vd_valid
                //         || vau0                               |viu               || |r2w1                                                                                               | decode_stop
                //         || |vau1             dhazard          ||vau0             || ||r3w1                                                                                              | | decode_jump
                //         || ||vau2            |vs              |||vau2            || ||| amo                                                                                             | | | mem_type_float
                //         || ||| amo           ||vt             |||| vgu           || ||| |utld                                                                                           | | | |
                //         || ||| |utld         |||vr            |||| |vlu          || ||| ||utst                                                                                          | | | |     mem_type
                //         || ||| ||utst        |||| vd          |||| ||vsu         || ||| |||     viufn                    vau0fn         VAU1fn         vau2fn         vs vt vr vd i     | | | |     |      mem_cmd
                //         || ||| |||           |||| |           |||| |||           || ||| |||     |                        |              |              |              |  |  |  |  |     | | | |     |      |
                List(Bits("b0_000_000",7),Bits("b000_0",4),Bits("b000_000",6),Bits("b0_000_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    R_,R_,R_,R_,imm_X,n,n,n,MTF_X,mtyp_X,mcmd_X),Array(

    BNE->       List(Bits("b1_000_000",7),Bits("b110_0",4),Bits("b000_000",6),Bits("b1_010_000",7),ML,MR,DW64,FP_,viu_BNE,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,R_,imm_X,n,n,n,MTF_X,mtyp_X,mcmd_X),
    BEQ->       List(Bits("b1_000_000",7),Bits("b110_0",4),Bits("b000_000",6),Bits("b1_010_000",7),ML,MR,DW64,FP_,viu_BEQ,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,R_,imm_X,n,n,n,MTF_X,mtyp_X,mcmd_X),
    BLT->       List(Bits("b1_000_000",7),Bits("b110_0",4),Bits("b000_000",6),Bits("b1_010_000",7),ML,MR,DW64,FP_,viu_BLT,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,R_,imm_X,n,n,n,MTF_X,mtyp_X,mcmd_X),
    BLTU->      List(Bits("b1_000_000",7),Bits("b110_0",4),Bits("b000_000",6),Bits("b1_010_000",7),ML,MR,DW64,FP_,viu_BLTU, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,R_,imm_X,n,n,n,MTF_X,mtyp_X,mcmd_X),
    BGE->       List(Bits("b1_000_000",7),Bits("b110_0",4),Bits("b000_000",6),Bits("b1_010_000",7),ML,MR,DW64,FP_,viu_BGE,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,R_,imm_X,n,n,n,MTF_X,mtyp_X,mcmd_X),
    BGEU->      List(Bits("b1_000_000",7),Bits("b110_0",4),Bits("b000_000",6),Bits("b1_010_000",7),ML,MR,DW64,FP_,viu_BGEU, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,R_,imm_X,n,n,n,MTF_X,mtyp_X,mcmd_X),

    LB->        List(Bits("b0_000_010",7),Bits("b100_1",4),Bits("b000_110",6),Bits("b0_000_010",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_N,mtyp_B,mcmd_XRD),
    LH->        List(Bits("b0_000_010",7),Bits("b100_1",4),Bits("b000_110",6),Bits("b0_000_010",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_N,mtyp_H,mcmd_XRD),
    LW->        List(Bits("b0_000_010",7),Bits("b100_1",4),Bits("b000_110",6),Bits("b0_000_010",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_N,mtyp_W,mcmd_XRD),
    LD->        List(Bits("b0_000_010",7),Bits("b100_1",4),Bits("b000_110",6),Bits("b0_000_010",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_N,mtyp_D,mcmd_XRD),
    LBU->       List(Bits("b0_000_010",7),Bits("b100_1",4),Bits("b000_110",6),Bits("b0_000_010",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_N,mtyp_BU,mcmd_XRD),
    LHU->       List(Bits("b0_000_010",7),Bits("b100_1",4),Bits("b000_110",6),Bits("b0_000_010",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_N,mtyp_HU,mcmd_XRD),
    LWU->       List(Bits("b0_000_010",7),Bits("b100_1",4),Bits("b000_110",6),Bits("b0_000_010",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_N,mtyp_WU,mcmd_XRD),
    SB->        List(Bits("b0_000_001",7),Bits("b110_0",4),Bits("b000_101",6),Bits("b0_000_001",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,R_,imm_B,n,n,n,MTF_N,mtyp_B,mcmd_XWR),
    SH->        List(Bits("b0_000_001",7),Bits("b110_0",4),Bits("b000_101",6),Bits("b0_000_001",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,R_,imm_B,n,n,n,MTF_N,mtyp_H,mcmd_XWR),
    SW->        List(Bits("b0_000_001",7),Bits("b110_0",4),Bits("b000_101",6),Bits("b0_000_001",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,R_,imm_B,n,n,n,MTF_N,mtyp_W,mcmd_XWR),
    SD->        List(Bits("b0_000_001",7),Bits("b110_0",4),Bits("b000_101",6),Bits("b0_000_001",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,R_,imm_B,n,n,n,MTF_N,mtyp_D,mcmd_XWR),
    AMOADD_W->  List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_W,mcmd_XA_ADD),
    AMOSWAP_W-> List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_W,mcmd_XA_SWAP),
    AMOAND_W->  List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_W,mcmd_XA_AND),
    AMOOR_W->   List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_W,mcmd_XA_OR),
    AMOMIN_W->  List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_W,mcmd_XA_MIN),
    AMOMAX_W->  List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_W,mcmd_XA_MAX),
    AMOMINU_W-> List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_W,mcmd_XA_MINU),
    AMOMAXU_W-> List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_W,mcmd_XA_MAXU),
    AMOADD_D->  List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_D,mcmd_XA_ADD),
    AMOSWAP_D-> List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_D,mcmd_XA_SWAP),
    AMOAND_D->  List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_D,mcmd_XA_AND),
    AMOOR_D->   List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_D,mcmd_XA_OR),
    AMOMIN_D->  List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_D,mcmd_XA_MIN),
    AMOMAX_D->  List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_D,mcmd_XA_MAX),
    AMOMINU_D-> List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_D,mcmd_XA_MINU),
    AMOMAXU_D-> List(Bits("b0_000_100",7),Bits("b110_1",4),Bits("b000_111",6),Bits("b0_000_100",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_0,y,n,n,MTF_N,mtyp_D,mcmd_XA_MAXU),
    FLW->       List(Bits("b0_000_010",7),Bits("b100_1",4),Bits("b000_110",6),Bits("b0_000_010",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RF,imm_I,y,n,n,MTF_Y,mtyp_W,mcmd_XRD),
    FLD->       List(Bits("b0_000_010",7),Bits("b100_1",4),Bits("b000_110",6),Bits("b0_000_010",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RF,imm_I,y,n,n,MTF_Y,mtyp_D,mcmd_XRD),
    FSW->       List(Bits("b0_000_001",7),Bits("b110_0",4),Bits("b000_101",6),Bits("b0_000_001",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RF,R_,R_,imm_B,n,n,n,MTF_Y,mtyp_W,mcmd_XWR),
    FSD->       List(Bits("b0_000_001",7),Bits("b110_0",4),Bits("b000_101",6),Bits("b0_000_001",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RF,R_,R_,imm_B,n,n,n,MTF_Y,mtyp_D,mcmd_XWR),

    UTIDX->     List(Bits("b1_000_000",7),Bits("b000_1",4),Bits("b000_000",6),Bits("b0_100_000",7),M0,M0,DW64,FP_,viu_IDX,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    R_,R_,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    MOVZ->      List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FP_,viu_MOVZ, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    MOVN->      List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FP_,viu_MOVN, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FMOVZ->     List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FP_,viu_MOVZ, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FMOVN->     List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FP_,viu_MOVN, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),

    LUI->       List(Bits("b1_000_000",7),Bits("b000_1",4),Bits("b000_000",6),Bits("b0_100_000",7),M0,MI,DW64,FP_,viu_MOV,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    R_,R_,R_,RX,imm_L,y,n,n,MTF_X,mtyp_X,mcmd_X),
    ADDI->      List(Bits("b1_000_000",7),Bits("b100_1",4),Bits("b000_000",6),Bits("b0_100_000",7),MR,MI,DW64,FP_,viu_ADD,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SLLI->      List(Bits("b1_000_000",7),Bits("b100_1",4),Bits("b000_000",6),Bits("b0_100_000",7),MR,MI,DW64,FP_,viu_SLL,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SLTI->      List(Bits("b1_000_000",7),Bits("b100_1",4),Bits("b000_000",6),Bits("b0_100_000",7),MR,MI,DW64,FP_,viu_SLT,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SLTIU->     List(Bits("b1_000_000",7),Bits("b100_1",4),Bits("b000_000",6),Bits("b0_100_000",7),MR,MI,DW64,FP_,viu_SLTU, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_X,mtyp_X,mcmd_X),
    XORI->      List(Bits("b1_000_000",7),Bits("b100_1",4),Bits("b000_000",6),Bits("b0_100_000",7),MR,MI,DW64,FP_,viu_XOR,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SRLI->      List(Bits("b1_000_000",7),Bits("b100_1",4),Bits("b000_000",6),Bits("b0_100_000",7),MR,MI,DW64,FP_,viu_SRL,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SRAI->      List(Bits("b1_000_000",7),Bits("b100_1",4),Bits("b000_000",6),Bits("b0_100_000",7),MR,MI,DW64,FP_,viu_SRA,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_X,mtyp_X,mcmd_X),
    ORI->       List(Bits("b1_000_000",7),Bits("b100_1",4),Bits("b000_000",6),Bits("b0_100_000",7),MR,MI,DW64,FP_,viu_OR,   DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_X,mtyp_X,mcmd_X),
    ANDI->      List(Bits("b1_000_000",7),Bits("b100_1",4),Bits("b000_000",6),Bits("b0_100_000",7),MR,MI,DW64,FP_,viu_AND,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_X,mtyp_X,mcmd_X),

    ADD->       List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FP_,viu_ADD,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SUB->       List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FP_,viu_SUB,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SLL->       List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FP_,viu_SLL,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SLT->       List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FP_,viu_SLT,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SLTU->      List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FP_,viu_SLTU, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    riscvXOR->  List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FP_,viu_XOR,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SRL->       List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FP_,viu_SRL,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SRA->       List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FP_,viu_SRA,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    riscvOR->   List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FP_,viu_OR,   DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    riscvAND->  List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FP_,viu_AND,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),

    ADDIW->     List(Bits("b1_000_000",7),Bits("b100_1",4),Bits("b000_000",6),Bits("b0_100_000",7),MR,MI,DW32,FP_,viu_ADD,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SLLIW->     List(Bits("b1_000_000",7),Bits("b100_1",4),Bits("b000_000",6),Bits("b0_100_000",7),MR,MI,DW32,FP_,viu_SLL,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SRLIW->     List(Bits("b1_000_000",7),Bits("b100_1",4),Bits("b000_000",6),Bits("b0_100_000",7),MR,MI,DW32,FP_,viu_SRL,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SRAIW->     List(Bits("b1_000_000",7),Bits("b100_1",4),Bits("b000_000",6),Bits("b0_100_000",7),MR,MI,DW32,FP_,viu_SRA,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,R_,R_,RX,imm_I,y,n,n,MTF_X,mtyp_X,mcmd_X),

    ADDW->      List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW32,FP_,viu_ADD,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SUBW->      List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW32,FP_,viu_SUB,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SLLW->      List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW32,FP_,viu_SLL,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SRLW->      List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW32,FP_,viu_SRL,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    SRAW->      List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW32,FP_,viu_SRA,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),

    FSGNJ_S->   List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPS,viu_FSJ,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FSGNJN_S->  List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPS,viu_FSJN, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FSGNJX_S->  List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPS,viu_FSJX, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FEQ_S->     List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPS,viu_FEQ,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FLT_S->     List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPS,viu_FLT,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FLE_S->     List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPS,viu_FLE,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FMIN_S->    List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPS,viu_FMIN, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FMAX_S->    List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPS,viu_FMAX, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FSGNJ_D->   List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPD,viu_FSJ,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FSGNJN_D->  List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPD,viu_FSJN, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FSGNJX_D->  List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPD,viu_FSJX, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FEQ_D->     List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPD,viu_FEQ,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FLT_D->     List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPD,viu_FLT,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FLE_D->     List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPD,viu_FLE,  DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FMIN_D->    List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPD,viu_FMIN, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FMAX_D->    List(Bits("b1_000_000",7),Bits("b110_1",4),Bits("b000_000",6),Bits("b0_010_000",7),ML,MR,DW64,FPD,viu_FMAX, DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),

    MUL->       List(Bits("b0_100_000",7),Bits("b110_1",4),Bits("b100_000",6),Bits("b0_010_000",7),M0,M0,DW__,FP_,viu_X,    DW64,vau0_M,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    MULH->      List(Bits("b0_100_000",7),Bits("b110_1",4),Bits("b100_000",6),Bits("b0_010_000",7),M0,M0,DW__,FP_,viu_X,    DW64,vau0_MH,  FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    MULHU->     List(Bits("b0_100_000",7),Bits("b110_1",4),Bits("b100_000",6),Bits("b0_010_000",7),M0,M0,DW__,FP_,viu_X,    DW64,vau0_MHU, FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    MULHSU->    List(Bits("b0_100_000",7),Bits("b110_1",4),Bits("b100_000",6),Bits("b0_010_000",7),M0,M0,DW__,FP_,viu_X,    DW64,vau0_MHSU,FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    MULW->      List(Bits("b0_100_000",7),Bits("b110_1",4),Bits("b100_000",6),Bits("b0_010_000",7),M0,M0,DW__,FP_,viu_X,    DW32,vau0_M,   FP_,VAU1_X,    FP_,vau2_X,    RX,RX,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),

    FADD_S->    List(Bits("b0_010_000",7),Bits("b110_1",4),Bits("b010_000",6),Bits("b0_010_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FPS,VAU1_ADD,  FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FSUB_S->    List(Bits("b0_010_000",7),Bits("b110_1",4),Bits("b010_000",6),Bits("b0_010_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FPS,VAU1_SUB,  FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FMUL_S->    List(Bits("b0_010_000",7),Bits("b110_1",4),Bits("b010_000",6),Bits("b0_010_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FPS,VAU1_MUL,  FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FMADD_S->   List(Bits("b0_010_000",7),Bits("b111_1",4),Bits("b010_000",6),Bits("b0_001_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FPS,VAU1_MADD, FP_,vau2_X,    RF,RF,RF,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FMSUB_S->   List(Bits("b0_010_000",7),Bits("b111_1",4),Bits("b010_000",6),Bits("b0_001_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FPS,VAU1_MSUB, FP_,vau2_X,    RF,RF,RF,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FNMSUB_S->  List(Bits("b0_010_000",7),Bits("b111_1",4),Bits("b010_000",6),Bits("b0_001_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FPS,VAU1_NMSUB,FP_,vau2_X,    RF,RF,RF,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FNMADD_S->  List(Bits("b0_010_000",7),Bits("b111_1",4),Bits("b010_000",6),Bits("b0_001_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FPS,VAU1_NMADD,FP_,vau2_X,    RF,RF,RF,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FADD_D->    List(Bits("b0_010_000",7),Bits("b110_1",4),Bits("b010_000",6),Bits("b0_010_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FPD,VAU1_ADD,  FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FSUB_D->    List(Bits("b0_010_000",7),Bits("b110_1",4),Bits("b010_000",6),Bits("b0_010_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FPD,VAU1_SUB,  FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FMUL_D->    List(Bits("b0_010_000",7),Bits("b110_1",4),Bits("b010_000",6),Bits("b0_010_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FPD,VAU1_MUL,  FP_,vau2_X,    RF,RF,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FMADD_D->   List(Bits("b0_010_000",7),Bits("b111_1",4),Bits("b010_000",6),Bits("b0_001_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FPD,VAU1_MADD, FP_,vau2_X,    RF,RF,RF,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FMSUB_D->   List(Bits("b0_010_000",7),Bits("b111_1",4),Bits("b010_000",6),Bits("b0_001_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FPD,VAU1_MSUB, FP_,vau2_X,    RF,RF,RF,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FNMSUB_D->  List(Bits("b0_010_000",7),Bits("b111_1",4),Bits("b010_000",6),Bits("b0_001_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FPD,VAU1_NMSUB,FP_,vau2_X,    RF,RF,RF,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FNMADD_D->  List(Bits("b0_010_000",7),Bits("b111_1",4),Bits("b010_000",6),Bits("b0_001_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FPD,VAU1_NMADD,FP_,vau2_X,    RF,RF,RF,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),

    FCVT_S_D->  List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPS,vau2_CDTS, RF,R_,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_D_S->  List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPD,vau2_CSTD, RF,R_,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_L_S->  List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPS,vau2_CFTL, RF,R_,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_LU_S-> List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPS,vau2_CFTLU,RF,R_,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_W_S->  List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPS,vau2_CFTW, RF,R_,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_WU_S-> List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPS,vau2_CFTWU,RF,R_,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_S_L->  List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPS,vau2_CLTF, RX,R_,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_S_LU-> List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPS,vau2_CLUTF,RX,R_,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_S_W->  List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPS,vau2_CWTF, RX,R_,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_S_WU-> List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPS,vau2_CWUTF,RX,R_,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    MXTF_S->    List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPS,vau2_MXTF, RX,R_,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    MFTX_S->    List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPS,vau2_MFTX, RF,R_,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_L_D->  List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPD,vau2_CFTL, RF,R_,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_LU_D-> List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPD,vau2_CFTLU,RF,R_,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_W_D->  List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPD,vau2_CFTW, RF,R_,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_WU_D-> List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPD,vau2_CFTWU,RF,R_,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_D_L->  List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPD,vau2_CLTF, RX,R_,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_D_LU-> List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPD,vau2_CLUTF,RX,R_,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_D_W->  List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPD,vau2_CWTF, RX,R_,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    FCVT_D_WU-> List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPD,vau2_CWUTF,RX,R_,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    MXTF_D->    List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPD,vau2_MXTF, RX,R_,R_,RF,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),
    MFTX_D->    List(Bits("b0_001_000",7),Bits("b100_1",4),Bits("b001_000",6),Bits("b0_100_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FPD,vau2_MFTX, RF,R_,R_,RX,imm_X,y,n,n,MTF_X,mtyp_X,mcmd_X),

    STOP->      List(Bits("b0_000_000",7),Bits("b000_0",4),Bits("b000_000",6),Bits("b0_000_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    R_,R_,R_,R_,imm_X,n,y,n,MTF_X,mtyp_X,mcmd_X),
    J->         List(Bits("b0_000_000",7),Bits("b000_0",4),Bits("b000_000",6),Bits("b0_000_000",7),M0,M0,DW__,FP_,viu_X,    DW__,vau0_X,   FP_,VAU1_X,    FP_,vau2_X,    R_,R_,R_,R_,imm_X,n,n,y,MTF_X,mtyp_X,mcmd_X)
  ))

  val valid::dhazard::shazard::bhazard::viu_t0::viu_t1::viu_dw::viu_fp::viu_fn::vau0_dw::vau0_fn::vau1_fp::vau1_fn::vau2_fp::vau2_fn::cs0 = cs
  val rtype = cs0.slice(0, 4)
  val itype::vd_valid::decode_stop::decode_jump::mem_type_float::mem_type::mem_cmd::Nil = cs0.slice(4, cs0.length)

  def decode_rtype(x: Bits): Bits = x(1)
  def active_rtype(x: Bits): Bool = x(0).toBool
  val rtype_vs :: rtype_vt :: rtype_vr :: rtype_vd :: Nil = rtype.map(x => decode_rtype(x))
  val vs_active :: vt_active :: vr_active :: vd_active :: Nil = rtype.map(x => active_rtype(x))

  val unmasked_valid_viu = valid(6)
  val unmasked_valid_vau0 = valid(5)
  val unmasked_valid_vau1 = valid(4)
  val unmasked_valid_vau2 = valid(3)
  val unmasked_valid_amo = valid(2)
  val unmasked_valid_utld = valid(1)
  val unmasked_valid_utst = valid(0)

  tagd := id_reg_tag
  val stop = decode_stop && io.vf.active
  val valid_branch = unmasked_valid_viu && isVIUBranch(viu_fn)
  val valid_jump = decode_jump && io.vf.active

  flushd := stop || valid_branch || valid_jump

  val replay_bits = MuxCase(Bits(0, SZ_ADDR), Array(
    valid_branch -> id_reg_pc,
    valid_jump -> (id_reg_pc + Cat(Fill(6, id_reg_inst(31)), id_reg_inst(31,7), Bits(0,1)))
  ))

  val br_taken_pc = id_reg_pc + Cat(Fill(19, id_reg_inst(31)),id_reg_inst(31,27),id_reg_inst(16,10), Bits(0,1))
  val br_not_taken_pc = id_reg_pc + UFix(4, SZ_ADDR)
  
  pcStage.io.vtToPC.replay_stop.valid := stop
  pcStage.io.vtToPC.replay_stop.bits.tag := id_reg_tag

  pcStage.io.vtToPC.replay_branch.valid := valid_branch
  pcStage.io.vtToPC.replay_branch.bits.pc := replay_bits
  pcStage.io.vtToPC.replay_branch.bits.tag := id_reg_tag

  pcStage.io.vtToPC.replay_jump.valid := valid_jump
  pcStage.io.vtToPC.replay_jump.bits.pc := replay_bits
  pcStage.io.vtToPC.replay_jump.bits.tag := id_reg_tag

  pcStage.io.vtToPVFB.stop:= stop
  pcStage.io.vtToPVFB.pc.valid:= valid_branch
  pcStage.io.vtToPVFB.pc.bits.taken:= br_taken_pc
  pcStage.io.vtToPVFB.pc.bits.not_taken:= br_not_taken_pc
  pcStage.io.vtToPVFB.pvfb_tag := id_reg_tag


  val vau1_rm = Wire(){Bits(width = 3)}
  val vau2_rm = Wire(){Bits(width = 3)}

  vau1_rm := id_reg_inst(11,9)
  vau2_rm := id_reg_inst(11,9)

  when (id_reg_inst(11,9) === Bits("b111",3))
  {
    vau1_rm := Bits(0,3)
    vau2_rm := Bits(0,3)
  }

  val unmasked_valid =
    unmasked_valid_viu ||
    unmasked_valid_vau0 || unmasked_valid_vau1 || unmasked_valid_vau2 ||
    unmasked_valid_amo || unmasked_valid_utld || unmasked_valid_utst

  val imm = MuxLookup(
    itype, Bits(0,SZ_DATA), Array(
      imm_0 -> Bits(0,65),
      imm_I -> Cat(Bits(0,1),Fill(52,id_reg_inst(21)),id_reg_inst(21,10)),
      imm_B -> Cat(Bits(0,1),Fill(52,id_reg_inst(31)),id_reg_inst(31,27),id_reg_inst(16,10)),
      imm_L -> Cat(Bits(0,1),Fill(32,id_reg_inst(26)),id_reg_inst(26,7),Bits(0,12))
    ))

  val cnt = Mux(io.vxu_cntq.valid, io.vxu_cntq.bits, Bits(0))
  val pvfb_regid_base = id_reg_tag * UFix(WIDTH_PVFB)
  val maskid_base = pvfb_regid_base >> UFix(3)
  val regid_base = ((cnt + pvfb_regid_base) >> UFix(3)) * io.vf.stride

  io.vxu_cntq.ready := io.vf.active & io.ready & unmasked_valid & !io.decoded.vd_zero & !stall

  io.aiw_cntb.valid := io.vf.active & io.ready & unmasked_valid & !io.decoded.vd_zero & !stall
  io.aiw_cntb.bits := cnt

  io.issue_to_aiw.markLast := stop
  io.issue_to_aiw.update_numCnt.valid := io.vf.active & io.ready & unmasked_valid & !io.decoded.vd_zero & !stall
  io.issue_to_aiw.update_numCnt.bits := numCnt_rtag

  val valid_common = io.vf.active & io.aiw_cntb.ready & !stall

  io.valid.viu := valid_common && unmasked_valid_viu
  io.valid.vau0 := valid_common && unmasked_valid_vau0
  io.valid.vau1 := valid_common && unmasked_valid_vau1
  io.valid.vau2 := valid_common && unmasked_valid_vau2
  io.valid.amo := valid_common && unmasked_valid_amo
  io.valid.utld := valid_common && unmasked_valid_utld
  io.valid.utst := valid_common && unmasked_valid_utst
  io.valid.vld := Bool(false)
  io.valid.vst := Bool(false)

  io.dhazard.vs := dhazard(3)
  io.dhazard.vt := dhazard(2)
  io.dhazard.vr := dhazard(1)
  io.dhazard.vd := dhazard(0)

  io.shazard.viu := Bool(false)
  io.shazard.vau0 := shazard(5)
  io.shazard.vau1 := shazard(4)
  io.shazard.vau2 := shazard(3)
  io.shazard.vgu := shazard(2)
  io.shazard.vlu := shazard(1)
  io.shazard.vsu := shazard(0)

  io.bhazard.r2wm := bhazard(6)
  io.bhazard.r1w1 := bhazard(5)
  io.bhazard.r2w1 := bhazard(4)
  io.bhazard.r3w1 := bhazard(3)
  io.bhazard.amo := bhazard(2)
  io.bhazard.utld := bhazard(1)
  io.bhazard.utst := bhazard(0)
  io.bhazard.vld := Bool(false)
  io.bhazard.vst := Bool(false)

  io.fn.vbr := Cat(viu_t0,viu_t1,viu_dw,viu_fp,viu_fn)
  io.fn.viu := Cat(viu_t0,viu_t1,viu_dw,viu_fp,viu_fn)
  io.fn.vau0 := Cat(vau0_dw,vau0_fn)
  io.fn.vau1 := Cat(vau1_fp,vau1_rm,vau1_fn)
  io.fn.vau2 := Cat(vau2_fp,vau2_rm,vau2_fn)

  val vs = Cat(rtype_vs,id_reg_inst(26,22))
  val vt = Cat(rtype_vt,id_reg_inst(21,17))
  val vr = Cat(rtype_vr,id_reg_inst(16,12))
  val vd = Cat(rtype_vd,id_reg_inst(31,27))

  val vs_m1 = Cat(Bits(0,1),vs(4,0)) - UFix(1,1)
  val vt_m1 = Cat(Bits(0,1),vt(4,0)) - UFix(1,1)
  val vr_m1 = Cat(Bits(0,1),vr(4,0)) - UFix(1,1)
  val vd_m1 = Cat(Bits(0,1),vd(4,0)) - UFix(1,1)

  mask_stall := decode_stop || decode_jump

  io.decoded.vlen := id_reg_vlen - cnt
  io.decoded.utidx := Mux(io.vxu_cntq.valid, io.vxu_cntq.bits, Bits(0)) + pvfb_regid_base
  io.decoded.vs := Mux(rtype_vs, vs_m1 + io.vf.nxregs, vs_m1) + regid_base
  io.decoded.vt := Mux(rtype_vt, vt_m1 + io.vf.nxregs, vt_m1) + regid_base
  io.decoded.vr := Mux(rtype_vr, vr_m1 + io.vf.nxregs, vr_m1) + regid_base
  io.decoded.vd := Mux(rtype_vd, vd_m1 + io.vf.nxregs, vd_m1) + regid_base
  io.decoded.vm := Bits(0,SZ_BMASK) + maskid_base
  io.decoded.vs_zero := vs === Bits(0,6)
  io.decoded.vt_zero := vt === Bits(0,6)
  io.decoded.vr_zero := vr === Bits(0,6)
  io.decoded.vd_zero := vd === Bits(0,6) && vd_valid || mask_stall
  io.decoded.vs_active := vs_active
  io.decoded.vt_active := vt_active
  io.decoded.vr_active := vr_active
  io.decoded.vd_active := vd_active
  io.decoded.mem.cmd := mem_cmd
  io.decoded.mem.typ := mem_type
  io.decoded.mem.typ_float := mem_type_float
  io.decoded.imm := imm
  io.decoded.cnt_valid := io.vxu_cntq.valid
  io.decoded.cnt := cnt
  io.decoded.active_mask := Bool(true)
  io.decoded.mask := id_reg_mask
  io.decoded.pvfb_tag := id_reg_tag

  val illegal_vd = vd_active && (vd(4,0) >= io.vf.nfregs && rtype_vd || vd(4,0) >= io.vf.nxregs && !rtype_vd)
  val illegal_vt = vt_active && (vt(4,0) >= io.vf.nfregs && rtype_vt || vt(4,0) >= io.vf.nxregs && !rtype_vt)
  val illegal_vs = vs_active && (vs(4,0) >= io.vf.nfregs && rtype_vs || vs(4,0) >= io.vf.nxregs && !rtype_vs)
  val illegal_vr = vr_active && (vs(4,0) >= io.vf.nfregs && rtype_vr || vr(4,0) >= io.vf.nxregs && !rtype_vr)

  io.irq.ma_inst := io.vf.active && imem_sb.io.pipereg.valid && imem_sb.io.pipereg.bits.pc(1,0) != Bits(0)
  io.irq.fault_inst := io.vf.active && io.vitlb_exception
  io.irq.illegal := io.vf.active && (~unmasked_valid && ~mask_stall || illegal_vd || illegal_vt || illegal_vs || illegal_vr)
  io.irq.pc_if := imem_sb.io.pipereg.bits.pc
  io.irq.pc_id := id_reg_pc
}
