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
import chisel3.util.experimental.BoringUtils
import zhoushan.Constant._

class Execution extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    // input
    val in = Vec(IssueWidth, Input(new MicroOp))
    val rs1_data = Vec(IssueWidth, Input(UInt(64.W)))
    val rs2_data = Vec(IssueWidth, Input(UInt(64.W)))
    // output
    val out = Vec(IssueWidth, Output(new MicroOp))
    val out_ecp = Vec(IssueWidth, Output(new ExCommitPacket))
    val rd_en = Vec(IssueWidth, Output(Bool()))
    val rd_paddr = Vec(IssueWidth, Output(UInt(log2Up(PrfSize).W)))
    val rd_data = Vec(IssueWidth, Output(UInt(64.W)))
    // from subsequent stage
    val flush = Input(Bool())
    // to previous stage
    val lsu_ready = Output(Bool())
    // dmem
    val dmem_st = new CacheBusIO
    val dmem_ld = new CacheBusIO
    // lsu early wakeup uop output
    val lsu_wakeup_uop = Output(new MicroOp)
  })
//transition between memory size and memory width
  def sz2wth(size: UInt) = {
    MuxLookup(size, 0.U)(List(
      0.U -> 8.U,
      1.U -> 16.U,
      2.U -> 32.U,
      3.U -> 64.U
    ))
  }

  val uop = io.in

  val reg_uop_lsu = RegInit(0.U.asTypeOf(new MicroOp))
  val reg_valid = RegNext(!io.lsu_ready) && io.lsu_ready

  when (uop(IssueWidth - 1).valid) {
    reg_uop_lsu := uop(IssueWidth - 1)
  }

  val in1_0 = Wire(Vec(IssueWidth, UInt(64.W)))
  val in2_0 = Wire(Vec(IssueWidth, UInt(64.W)))
  val in1 = Wire(Vec(IssueWidth, UInt(64.W)))
  val in2 = Wire(Vec(IssueWidth, UInt(64.W)))

  for (i <- 0 until IssueWidth) {
    in1_0(i) := MuxLookup(uop(i).rs1_src, 0.U, Array(
      s"b$RS_FROM_RF".U  -> io.rs1_data(i),
      s"b$RS_FROM_IMM".U -> SignExt32_64(uop(i).imm),
      s"b$RS_FROM_PC".U  -> ZeroExt32_64(uop(i).pc)
    ))(63, 0)

    in2_0(i) := MuxLookup(uop(i).rs2_src, 0.U, Array(
      s"b$RS_FROM_RF".U  -> io.rs2_data(i),
      s"b$RS_FROM_IMM".U -> SignExt32_64(uop(i).imm),
      s"b$RS_FROM_PC".U  -> ZeroExt32_64(uop(i).pc)
    ))(63, 0)

    in1(i) := Mux(uop(i).w_type,
                  Mux(uop(i).alu_code === s"b$ALU_SRL".U,
                      ZeroExt32_64(in1_0(i)(31, 0)),
                      SignExt32_64(in1_0(i)(31, 0))),
                  in1_0(i))
    in2(i) := Mux(uop(i).w_type, SignExt32_64(in2_0(i)(31, 0)), in2_0(i))
  }

  val pipe0 = Module(new ExPipe0)
  pipe0.io.uop := uop(0)
  pipe0.io.in1 := in1(0)
  pipe0.io.in2 := in2(0)

  val pipe1 = Module(new ExPipe1)
  pipe1.io.uop := uop(1)
  pipe1.io.in1 := in1(1)
  pipe1.io.in2 := in2(1)

  val pipe2 = Module(new ExPipe2)
  pipe2.io.uop := uop(2)
  pipe2.io.in1 := in1(2)
  pipe2.io.in2 := in2(2)
  io.lsu_ready := pipe2.io.ready
  pipe2.io.dmem_st <> io.dmem_st
  pipe2.io.dmem_ld <> io.dmem_ld
  pipe2.io.flush := io.flush

  // early wakeup signal for issue unit
  io.lsu_wakeup_uop := 0.U.asTypeOf(new MicroOp)
  when (pipe2.io.wakeup) {
    io.lsu_wakeup_uop := reg_uop_lsu
  }

  // pipeline registers

  val out_uop = RegInit(VecInit(Seq.fill(IssueWidth)(0.U.asTypeOf(new MicroOp))))
  val out_ecp = RegInit(VecInit(Seq.fill(IssueWidth)(0.U.asTypeOf(new ExCommitPacket))))
  val out_rd_en = WireInit(VecInit(Seq.fill(IssueWidth)(false.B)))
  val out_rd_paddr = WireInit(VecInit(Seq.fill(IssueWidth)(0.U(log2Up(PrfSize).W))))
  val out_rd_data = WireInit(VecInit(Seq.fill(IssueWidth)(0.U(64.W))))

  when (io.flush) {
    for (i <- 0 until IssueWidth) {
      out_uop(i) := 0.U.asTypeOf(new MicroOp)
      out_ecp(i) := 0.U.asTypeOf(new ExCommitPacket)
      out_rd_en(i) := false.B
      out_rd_paddr(i) := 0.U
      out_rd_data(i) := 0.U
    }
    reg_uop_lsu := 0.U.asTypeOf(new MicroOp)
  } .otherwise {
    // pipe 0
    val pipe0_ecp = pipe0.io.ecp
    out_uop     (0) := uop(0)
    out_ecp     (0) := pipe0.io.ecp
    out_rd_en   (0) := uop(0).rd_en
    out_rd_paddr(0) := uop(0).rd_paddr
    out_rd_data (0) := pipe0.io.ecp.rd_data
    // formal test used
    out_uop     (0).rd_data  := pipe0.io.ecp.rd_data
    when(pipe0_ecp.jmp && pipe0_ecp.jmp_valid && uop(0).valid) {
      out_uop   (0).npc      := pipe0_ecp.jmp_pc
      assume(pipe0_ecp.jmp_pc(1,0) === 0.U)
    }
    val csr_fv_wdata = WireInit(0.U(32.W))
    val csr_fv_addr = WireInit(0.U(12.W))
    val csr_fv_wr = WireInit(false.B)

    BoringUtils.addSink(out_uop(0).csr_addr, "csr_fv_addr")
    BoringUtils.addSink(out_uop(0).csr_wdata, "csr_fv_wdata")
    BoringUtils.addSink(out_uop(0).csr_wen, "csr_fv_wr")

    val csr_fv_mhartid    = WireInit(0.U(64.W))
    val csr_fv_mstatus    = WireInit(0.U(64.W))
    val csr_fv_mie        = WireInit(0.U(64.W))
    val csr_fv_mtvec      = WireInit(0.U(64.W))
    val csr_fv_mscratch   = WireInit(0.U(64.W))
    val csr_fv_mepc       = WireInit(0.U(64.W))
    val csr_fv_mcause     = WireInit(0.U(64.W))
    BoringUtils.addSink(csr_fv_mhartid , "csr_fv_mhartid")
    BoringUtils.addSink(csr_fv_mstatus , "csr_fv_mstatus")
    BoringUtils.addSink(csr_fv_mie     , "csr_fv_mie")
    BoringUtils.addSink(csr_fv_mtvec   , "csr_fv_mtvec")
    BoringUtils.addSink(csr_fv_mscratch, "csr_fv_mscratch")
    BoringUtils.addSink(csr_fv_mepc    , "csr_fv_mepc")
    BoringUtils.addSink(csr_fv_mcause  , "csr_fv_mcause")
    out_uop(0).csr_data.mhartid     :=  csr_fv_mhartid
    out_uop(0).csr_data.mstatus     :=  csr_fv_mstatus
    out_uop(0).csr_data.mie         :=  csr_fv_mie
    out_uop(0).csr_data.mtvec       :=  csr_fv_mtvec
    out_uop(0).csr_data.mscratch    :=  csr_fv_mscratch
    out_uop(0).csr_data.mepc        :=  csr_fv_mepc
    out_uop(0).csr_data.mcause      :=  csr_fv_mcause

    out_uop(0).csr_addr := csr_fv_addr
    out_uop(0).csr_wdata := csr_fv_wdata
    out_uop(0).csr_wen := csr_fv_wr
    // pipe 1
    val pipe1_ecp = pipe1.io.ecp
    out_uop     (1) := uop(1)
    out_ecp     (1) := pipe1.io.ecp
    out_rd_en   (1) := uop(1).rd_en
    out_rd_paddr(1) := uop(1).rd_paddr
    out_rd_data (1) := pipe1.io.ecp.rd_data

    out_uop     (1).rd_data  := pipe1.io.ecp.rd_data
    when(pipe1_ecp.jmp && pipe1_ecp.jmp_valid && uop(1).valid) {
      out_uop   (1).npc      := pipe1_ecp.jmp_pc
      assume(pipe1_ecp.jmp_pc(1,0) === 0.U)
    }
    // pipe 2
    val pipe2_ecp = pipe2.io.ecp
    out_uop     (2) := Mux(reg_valid, reg_uop_lsu, 0.U.asTypeOf(new MicroOp))
    out_ecp     (2) := pipe2.io.ecp
    out_rd_en   (2) := Mux(reg_valid, reg_uop_lsu.rd_en, false.B)
    out_rd_paddr(2) := reg_uop_lsu.rd_paddr
    out_rd_data (2) := pipe2.io.ecp.rd_data

    out_uop     (2).rd_data  := pipe2.io.ecp.rd_data

    // mem_info connection
    val raw_mem_addr  = WireInit(UInt(32.W),0.U)
    val raw_mem_width = WireInit(UInt(2.W),0.U)

    BoringUtils.addSink(raw_mem_addr, "raw_mem_addr")
    BoringUtils.addSink(raw_mem_width,"raw_mem_width")

    out_uop     (2).mem_info.write.valid    := io.dmem_st.resp.fire
    out_uop     (2).mem_info.write.addr     := raw_mem_addr
    out_uop     (2).mem_info.write.memWidth := sz2wth(raw_mem_width)
    out_uop     (2).mem_info.write.data     := io.dmem_st.req.bits.wdata

    out_uop     (2).mem_info.read.valid     := io.dmem_ld.resp.fire
    out_uop     (2).mem_info.read.addr      := raw_mem_addr
    out_uop     (2).mem_info.read.memWidth  := sz2wth(raw_mem_width)
    out_uop     (2).mem_info.read.data      := io.dmem_ld.resp.bits.rdata
  }

  io.out      := out_uop
  io.out_ecp  := out_ecp
  io.rd_en    := out_rd_en
  io.rd_paddr := out_rd_paddr
  io.rd_data  := out_rd_data

}

// Execution Pipe 0
//   1 ALU + 1 CSR + 1 FENCEI
class ExPipe0 extends Module {
  val io = IO(new Bundle {
    // input
    val uop = Input(new MicroOp)
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    // output
    val ecp = Output(new ExCommitPacket)
  })

  val alu = Module(new Alu)
  alu.io.in1 := io.in1
  alu.io.in2 := io.in2

  val csr = Module(new Csr)
  csr.io.in1 := io.in1

  val fence = Module(new Fence)

  // default input and output
  alu.io.uop := 0.U.asTypeOf(new MicroOp)
  csr.io.uop := 0.U.asTypeOf(new MicroOp)
  fence.io.uop := 0.U.asTypeOf(new MicroOp)
  io.ecp := 0.U.asTypeOf(new ExCommitPacket)

  when (io.uop.fu_code === s"b$FU_ALU".U || io.uop.fu_code === s"b$FU_JMP".U) {
    alu.io.uop := io.uop
    io.ecp := alu.io.ecp
  } .elsewhen (io.uop.fu_code === s"b$FU_SYS".U && io.uop.sys_code =/= s"b$SYS_FENCE".U && io.uop.sys_code =/= s"b$SYS_FENCEI".U) {
    csr.io.uop := io.uop
    io.ecp := csr.io.ecp
  } .otherwise {
    fence.io.uop := io.uop
    io.ecp := fence.io.ecp
  }
}

// Execution Pipe 1
//   1 ALU
class ExPipe1 extends Module {
  val io = IO(new Bundle {
    // input
    val uop = Input(new MicroOp)
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    // output
    val ecp = Output(new ExCommitPacket)
  })

  val alu = Module(new Alu)
  alu.io.uop := io.uop
  alu.io.in1 := io.in1
  alu.io.in2 := io.in2

  io.ecp := alu.io.ecp
}

// Execution Pipe 2
//   1 LSU
class ExPipe2 extends Module {
  val io = IO(new Bundle {
    // input
    val uop = Input(new MicroOp)
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    // output
    val ecp = Output(new ExCommitPacket)
    val ready = Output(Bool())
    // dmem
    val dmem_st = new CacheBusIO
    val dmem_ld = new CacheBusIO
    // flush signal
    val flush = Input(Bool())
    // early wakeup for issue unit
    val wakeup = Output(Bool())
  })

  val lsu = Module(new Lsu)
  lsu.io.uop := io.uop
  lsu.io.in1 := io.in1
  lsu.io.in2 := io.in2
  lsu.io.dmem_st <> io.dmem_st
  lsu.io.dmem_ld <> io.dmem_ld
  lsu.io.flush := io.flush

  io.ecp := lsu.io.ecp
  io.ready := !lsu.io.busy
  io.wakeup := lsu.io.wakeup
}
