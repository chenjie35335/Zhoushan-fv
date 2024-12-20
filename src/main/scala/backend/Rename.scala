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

// ref: Weiwu Hu. Computer Architecture (2nd ed). THU Press. (page 136)
trait PrfStateConstant {
  val FREE      = 0.asUInt(2.W)
  val MAPPED    = 1.asUInt(2.W)
  val EXECUTED  = 2.asUInt(2.W)
  val COMMITTED = 3.asUInt(2.W)
}

class Rename extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new MicroOpVec(DecodeWidth)))
    val out = Decoupled(new MicroOpVec(DecodeWidth))
    val avail_list = Output(UInt(PrfSize.W))
    val flush = Input(Bool())
    // from ex stage
    val exe = Vec(IssueWidth, Input(new MicroOp))
    // from commit stage
    val cm_recover = Input(Bool())
    val cm = Vec(CommitWidth, Input(new MicroOp))
  })

  val en = io.out.ready && io.in.valid

  val in_uop = io.in.bits.vec
  val uop = WireInit(VecInit(Seq.fill(DecodeWidth)(0.U.asTypeOf(new MicroOp))))
  uop := in_uop

  val pst = Module(new PrfStateTable)
  pst.io.en := en
  for (i <- 0 until DecodeWidth) {
    pst.io.rd_req(i) := in_uop(i).valid && in_uop(i).rd_en
  }
  for (i <- 0 until IssueWidth) {
    pst.io.exe(i) := Mux(io.exe(i).valid && io.exe(i).rd_en, io.exe(i).rd_paddr, 0.U)
  }
  for (i <- 0 until CommitWidth) {
    pst.io.cm(i) := Mux(io.cm(i).valid && io.cm(i).rd_en, io.cm(i).rd_paddr, 0.U)
    pst.io.free(i) := Mux(io.cm(i).valid && io.cm(i).rd_en, io.cm(i).rd_ppaddr, 0.U)
  }
  pst.io.cm_recover := io.cm_recover
  io.avail_list := pst.io.avail_list

  val rt = Module(new RenameTable)
  rt.io.en := en
  rt.io.in := in_uop
  for (i <- 0 until DecodeWidth) {
    uop(i).rs1_paddr  := rt.io.rs1_paddr(i)
    uop(i).rs2_paddr  := rt.io.rs2_paddr(i)
    rt.io.rd_addr(i)  := Mux(in_uop(i).valid && in_uop(i).rd_en, in_uop(i).rd_addr, 0.U)
    uop(i).rd_ppaddr  := rt.io.rd_ppaddr(i)
    rt.io.rd_paddr(i) := pst.io.rd_paddr(i)
    uop(i).rd_paddr   := pst.io.rd_paddr(i)
  }
  rt.io.cm_recover  := io.cm_recover
  for (i <- 0 until CommitWidth) {
    rt.io.cm_rd_addr(i)  := Mux(io.cm(i).valid && io.cm(i).rd_en, io.cm(i).rd_addr, 0.U)
    rt.io.cm_rd_paddr(i) := io.cm(i).rd_paddr
  }

  io.in.ready := pst.io.allocatable && io.out.ready

  // pipeline registers

  val out_uop = RegInit(VecInit(Seq.fill(DecodeWidth)(0.U.asTypeOf(new MicroOp))))
  val out_valid = RegInit(false.B)

  when (io.flush) {
    for (i <- 0 until DecodeWidth) {
      out_uop(i) := 0.U.asTypeOf(new MicroOp)
    }
    out_valid := false.B
  } .otherwise {
    for (i <- 0 until DecodeWidth) {
      out_uop(i) := Mux(RegNext(uop(i).valid), RegNext(uop(i)), 0.U.asTypeOf(new MicroOp))
    }
    out_valid := io.in.valid
  }

  io.out.bits.vec := out_uop
  io.out.valid := out_valid

  if (DebugRename) {
    for (i <- 0 until DecodeWidth) {
      val u = io.out.bits.vec(i)
      when (u.valid) {
        printf("%d: [RR %d ] pc=%x inst=%x rs1=%d->%d rs2=%d->%d rd(en=%x)=%d->%d\n", DebugTimer(), i.U,
                u.pc, u.inst, u.rs1_addr, u.rs1_paddr, u.rs2_addr, u.rs2_paddr, u.rd_en, u.rd_addr, u.rd_paddr)
      }
    }
  }

}

class RenameTable extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val en = Input(Bool())
    // rs1, rs2
    val in = Vec(DecodeWidth, Input(new MicroOp))
    val rs1_paddr = Vec(DecodeWidth, Output(UInt(log2Up(PrfSize).W)))
    val rs2_paddr = Vec(DecodeWidth, Output(UInt(log2Up(PrfSize).W)))
    // rd
    val rd_addr = Vec(DecodeWidth, Input(UInt(5.W)))
    val rd_ppaddr = Vec(DecodeWidth, Output(UInt(log2Up(PrfSize).W)))
    val rd_paddr = Vec(DecodeWidth, Input(UInt(log2Up(PrfSize).W)))
    // from commit stage
    val cm_recover = Input(Bool())
    val cm_rd_addr = Vec(CommitWidth, Input(UInt(5.W)))
    val cm_rd_paddr = Vec(CommitWidth, Input(UInt(log2Up(PrfSize).W)))
  })

  val spec_table = RegInit(VecInit(Seq.tabulate(32)(i => i.U(log2Up(PrfSize).W))))
  val arch_table = RegInit(VecInit(Seq.tabulate(32)(i => i.U(log2Up(PrfSize).W))))

  for (i <- 0 until DecodeWidth) {
    io.rs1_paddr(i) := spec_table(io.in(i).rs1_addr)
    io.rs2_paddr(i) := spec_table(io.in(i).rs2_addr)
    io.rd_ppaddr(i) := spec_table(io.rd_addr(i))
  }

  // in-group RAW dependency check
  // todo: currently only support 2-way rename
  when ((io.in(1).rs1_addr === io.rd_addr(0)) && (io.rd_addr(0) =/= 0.U)) {
    io.rs1_paddr(1) := io.rd_paddr(0)
  }
  when ((io.in(1).rs2_addr === io.rd_addr(0)) && (io.rd_addr(0) =/= 0.U)) {
    io.rs2_paddr(1) := io.rd_paddr(0)
  }
  // in-group WAW dependency check
  when ((io.in(1).rd_addr === io.rd_addr(0)) && (io.rd_addr(0) =/= 0.U)) {
    io.rd_ppaddr(1) := io.rd_paddr(0)
  }

  when (io.cm_recover) {
    spec_table := arch_table
  } .otherwise {
    // be careful with WAW dependency here
    for (i <- 0 until DecodeWidth) {
      when (io.rd_addr(i) =/= 0.U && io.en) {
        spec_table(io.rd_addr(i)) := io.rd_paddr(i)
      }
    }
    for (i <- 0 until CommitWidth) {
      when (io.cm_rd_addr(i) =/= 0.U) {
        arch_table(io.cm_rd_addr(i)) := io.cm_rd_paddr(i)
      }
    }
  }

  BoringUtils.addSource(arch_table, "arch_rename_table")

  if (DebugRenameVerbose) {
    for (i <- 0 until 32 / 8) {
      printf("%d: [RR RT] ", DebugTimer())
      for (j <- 0 until 8) {
        val idx = i * 8 + j
        printf("%d-%d(%d)\t", idx.U, spec_table(idx), arch_table(idx))
      }
      printf("\n")
    }
  }

}

class PrfStateTable extends Module with PrfStateConstant with ZhoushanConfig {
  val io = IO(new Bundle {
    val en = Input(Bool())
    val allocatable = Output(Bool())
    // allocate free physical registers
    val rd_req = Vec(DecodeWidth, Input(Bool()))
    val rd_paddr = Vec(DecodeWidth, Output(UInt(log2Up(PrfSize).W)))
    // update prf state
    val exe = Vec(IssueWidth, Input(UInt(log2Up(PrfSize).W)))
    val cm = Vec(CommitWidth, Input(UInt(log2Up(PrfSize).W)))
    val free = Vec(CommitWidth, Input(UInt(log2Up(PrfSize).W)))
    val cm_recover = Input(Bool())
    // pass avail list to issue unit
    val avail_list = Output(UInt(PrfSize.W))
  })

  val table = RegInit(VecInit(Seq.fill(32)(COMMITTED) ++ Seq.fill(PrfSize - 32)(FREE)))

  val free_list = Cat(table.map(_ === FREE).reverse)
  val free_count = PopCount(free_list)
  io.allocatable := (free_count >= DecodeWidth.U)

  val avail_list = Cat(table.map(_ === EXECUTED).reverse) | Cat(table.map(_ === COMMITTED).reverse)
  io.avail_list := avail_list

  val fl0 = free_list
  io.rd_paddr(0) := Mux(io.en && io.rd_req(0), PriorityEncoder(fl0), 0.U)

  val fl1 = fl0 & ~UIntToOH(io.rd_paddr(0), PrfSize)
  io.rd_paddr(1) := Mux(io.en && io.rd_req(1), PriorityEncoder(fl1), 0.U)

  for (i <- 0 until DecodeWidth) {
    when (io.en && io.rd_req(i)) {
      table(io.rd_paddr(i)) := MAPPED
    }
  }

  for (i <- 0 until IssueWidth) {
    when (io.exe(i) =/= 0.U) {
      table(io.exe(i)) := EXECUTED
    }
  }

  for (i <- 0 until CommitWidth) {
    when (io.cm(i) =/= 0.U) {
      table(io.cm(i)) := COMMITTED
    }
  }

  for (i <- 0 until CommitWidth) {
    when (io.free(i) =/= 0.U) {
      table(io.free(i)) := FREE
    }
  }

  when (io.cm_recover) {
    for (i <- 0 until PrfSize) {
      when (table(i) =/= COMMITTED) {
        table(i) := FREE
      }
    }
  }

  // default
  table(0) := COMMITTED

  if (DebugRenameVerbose) {
    for (i <- 0 until PrfSize / 16) {
      printf("%d: [RRPST] ", DebugTimer())
      for (j <- 0 until 16) {
        val idx = i * 16 + j
        printf("%d-%d\t", idx.U, table(idx))
      }
      printf("\n")
    }
  }

}
