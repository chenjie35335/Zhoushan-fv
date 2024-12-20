/**************************************************************************************
* Copyright (c) 2021 Li Shi
*
* Zhoushan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
* FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import difftest._
import zhoushan.Constant._

object Csrs {
  val mhartid  = "hf14".U
  val mstatus  = "h300".U
  val mie      = "h304".U
  val mtvec    = "h305".U
  val mscratch = "h340".U
  val mepc     = "h341".U
  val mcause   = "h342".U
  val mip      = "h344".U
  val mcycle   = "hb00".U
  val minstret = "hb02".U
}

class Csrs_Data extends Bundle{
  val mhartid   = UInt(64.W)
  val mstatus   = UInt(64.W)
  val mie       = UInt(64.W)
  val mtvec     = UInt(64.W)
  val mscratch  = UInt(64.W)
  val mepc      = UInt(64.W)
  val mcause    = UInt(64.W)
}

class Csr extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp())
    val in1 = Input(UInt(64.W))
    val ecp = Output(new ExCommitPacket)
  })

  val uop = io.uop

  val in1 = io.in1
  val sys_code = uop.sys_code
  val csr_rw = (sys_code === s"b$SYS_CSRRW".U) ||
               (sys_code === s"b$SYS_CSRRS".U) ||
               (sys_code === s"b$SYS_CSRRC".U)
  val csr_jmp = WireInit(Bool(), false.B)
  val csr_jmp_pc = WireInit(UInt(32.W), 0.U)

  // CSR register definition

  val mhartid   = RegInit(UInt(64.W), 0.U)
  val mstatus   = RegInit(UInt(64.W), "h00001800".U)
  val mie       = RegInit(UInt(64.W), 0.U)
  val mtvec     = RegInit(UInt(64.W), 0.U)
  val mscratch  = RegInit(UInt(64.W), 0.U)
  val mepc      = RegInit(UInt(64.W), 0.U)
  val mcause    = RegInit(UInt(64.W), 0.U)

  BoringUtils.addSource(mhartid , "csr_fv_mhartid")
  BoringUtils.addSource(mstatus , "csr_fv_mstatus")
  BoringUtils.addSource(mie     , "csr_fv_mie")
  BoringUtils.addSource(mtvec   , "csr_fv_mtvec")
  BoringUtils.addSource(mscratch, "csr_fv_mscratch")
  BoringUtils.addSource(mepc    , "csr_fv_mepc")
  BoringUtils.addSource(mcause  , "csr_fv_mcause")

  BoringUtils.addSource(mstatus, "csr_mstatus")
  BoringUtils.addSource(mie(7).asBool, "csr_mie_mtie")
  BoringUtils.addSource(mtvec(31, 2), "csr_mtvec_idx")

  // interrupt for mip
  val mtip      = WireInit(UInt(1.W), 0.U)
  BoringUtils.addSink(mtip, "csr_mip_mtip")
  BoringUtils.addSource(mtip, "csr_mip_mtip_intr")

  val mcycle    = WireInit(UInt(64.W), 0.U)
  val minstret  = WireInit(UInt(64.W), 0.U)

  BoringUtils.addSink(mcycle, "csr_mcycle")
  BoringUtils.addSink(minstret, "csr_minstret")

  // CSR write function with side effect

  def mstatusWriteFunction(mstatus: UInt): UInt = {
    def get_mstatus_xs(mstatus: UInt): UInt = mstatus(16, 15)
    def get_mstatus_fs(mstatus: UInt): UInt = mstatus(14, 13)
    val mstatus_sd = ((get_mstatus_xs(mstatus) === "b11".U) || (get_mstatus_fs(mstatus) === "b11".U)).asUInt
    val mstatus_new = Cat(mstatus_sd, mstatus(62, 0))
    mstatus_new
  }
  // ECALL
  when (sys_code === s"b$SYS_ECALL".U) {
    mepc := uop.pc
    mcause := 11.U  // env call from M-mode
    mstatus := Cat(mstatus(63, 8), mstatus(3), mstatus(6, 4), 0.U, mstatus(2, 0))
    csr_jmp := true.B
    csr_jmp_pc := Cat(mtvec(31, 2), Fill(2, 0.U))
  }

  // MRET
  when (sys_code === s"b$SYS_MRET".U) {
    mstatus := Cat(mstatus(63, 8), 1.U, mstatus(6, 4), mstatus(7), mstatus(2, 0))
    csr_jmp := true.B
    csr_jmp_pc := mepc(31, 0)
  }

  // interrupt
  val intr         = WireInit(Bool(), false.B)
  val intr_mstatus = WireInit(UInt(64.W), "h00001800".U)
  val intr_mepc    = WireInit(UInt(64.W), 0.U)
  val intr_mcause  = WireInit(UInt(64.W), 0.U)

  BoringUtils.addSink(intr, "intr")
  BoringUtils.addSink(intr_mstatus, "intr_mstatus")
  BoringUtils.addSink(intr_mepc, "intr_mepc")
  BoringUtils.addSink(intr_mcause, "intr_mcause")

  //verification signals

  when (intr) {
    mstatus := intr_mstatus
    mepc := intr_mepc
    mcause := intr_mcause
  }

  // CSR register map

  val csr_map = Map(
    MaskedRegMap(Csrs.mhartid , mhartid ),
    MaskedRegMap(Csrs.mstatus , mstatus , "hffffffffffffffff".U, mstatusWriteFunction),
    MaskedRegMap(Csrs.mie     , mie     ),
    MaskedRegMap(Csrs.mtvec   , mtvec   ),
    MaskedRegMap(Csrs.mscratch, mscratch),
    MaskedRegMap(Csrs.mepc    , mepc    ),
    MaskedRegMap(Csrs.mcause  , mcause  ),
    // skip mip
    MaskedRegMap(Csrs.mcycle  , mcycle  ),
    MaskedRegMap(Csrs.minstret, minstret)
  )

  // CSR register read/write

  val addr = uop.inst(31, 20)
  val rdata = WireInit(UInt(64.W), 0.U)
  val wdata = Wire(UInt(64.W))
  val wen = csr_rw

  wdata := MuxLookup(uop.sys_code, 0.U, Array(
    s"b$SYS_CSRRW".U -> in1,
    s"b$SYS_CSRRS".U -> (rdata | in1),
    s"b$SYS_CSRRC".U -> (rdata & ~in1)
  ))

  MaskedRegMap.access(csr_map, addr, rdata, wdata, wen)

  // mip access
  when (Csrs.mip === addr) {
    rdata := 0.U
  }

  io.ecp.store_valid := false.B
  io.ecp.mmio := false.B
  io.ecp.jmp_valid := csr_jmp
  io.ecp.jmp := csr_jmp
  io.ecp.jmp_pc := csr_jmp_pc
  io.ecp.mis := Mux(csr_jmp,
                    (uop.pred_br && (csr_jmp_pc =/= uop.pred_bpc)) || !uop.pred_br,
                    uop.pred_br)
  io.ecp.rd_data := rdata

  //verification
  if(ZhoushanConfig.EnableFormal) {
    val csr_vmap = Map( //mip寄存器比较麻烦，目前这里被认为是只读的，所以我不想管他
      //其他的两个对于验证来说没有意义，因此不予考虑
      //MaskedRegMap(Csrs.mhartid, mhartid),
      MaskedRegMap(Csrs.mstatus, mstatus, "hffffffffffffffff".U, mstatusWriteFunction),
      MaskedRegMap(Csrs.mie, mie),
      MaskedRegMap(Csrs.mtvec, mtvec),
      MaskedRegMap(Csrs.mscratch, mscratch),
      MaskedRegMap(Csrs.mepc, mepc),
      MaskedRegMap(Csrs.mcause, mcause)
    )
    // assume the exist register
    when(csr_rw) {
      assume(MaskedRegMap.exists(csr_vmap, addr))
    }
    //
    val csr_ndata = WireInit(0.U(32.W))
    MaskedRegMap.rawData(csr_vmap, addr, wdata , csr_ndata, wen)

    // 添加飞线
    BoringUtils.addSource(addr, "csr_fv_addr")
    BoringUtils.addSource(csr_rw, "csr_fv_wr")
    BoringUtils.addSource(csr_ndata, "csr_fv_wdata")
  }

  // difftest for CSR state

  if (ZhoushanConfig.EnableDifftest) {
    val dt_cs = Module(new DifftestCSRState)
    dt_cs.io.clock          := clock
    dt_cs.io.coreid         := 0.U
    dt_cs.io.priviledgeMode := 3.U        // machine mode
    dt_cs.io.mstatus        := mstatus
    dt_cs.io.sstatus        := mstatus & "h80000003000de122".U
    dt_cs.io.mepc           := mepc
    dt_cs.io.sepc           := 0.U
    dt_cs.io.mtval          := 0.U
    dt_cs.io.stval          := 0.U
    dt_cs.io.mtvec          := mtvec
    dt_cs.io.stvec          := 0.U
    dt_cs.io.mcause         := mcause
    dt_cs.io.scause         := 0.U
    dt_cs.io.satp           := 0.U
    dt_cs.io.mip            := 0.U
    dt_cs.io.mie            := mie
    dt_cs.io.mscratch       := mscratch
    dt_cs.io.sscratch       := 0.U
    dt_cs.io.mideleg        := 0.U
    dt_cs.io.medeleg        := 0.U
  }
  if(ZhoushanConfig.EnableFormal && ZhoushanConfig.CommitWidth <= 1) {
    val resultCSRWire = rvspeccore.checker.ConnectCheckerResult.makeCSRSource()(64, ZhoushanConfig.FormalConfig)
    resultCSRWire.mstatus        := mstatus
    resultCSRWire.mepc           := mepc
    resultCSRWire.sepc           := 0.U
    resultCSRWire.mtval          := 0.U
    resultCSRWire.stval          := 0.U
    resultCSRWire.mtvec          := mtvec
    resultCSRWire.stvec          := 0.U
    resultCSRWire.mcause         := mcause
    resultCSRWire.scause         := 0.U
    resultCSRWire.satp           := 0.U
    resultCSRWire.mip            := 0.U
    resultCSRWire.mie            := mie
    resultCSRWire.mscratch       := mscratch
    resultCSRWire.sscratch       := 0.U
    resultCSRWire.mideleg        := 0.U
    resultCSRWire.medeleg        := 0.U
  }
}
