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
import rvspeccore.checker.ConnectCheckerResult

class Prf extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val in = Vec(IssueWidth, Input(new MicroOp))
    val out = Vec(IssueWidth, Output(new MicroOp))
    val rs1_data = Vec(IssueWidth, Output(UInt(64.W)))
    val rs2_data = Vec(IssueWidth, Output(UInt(64.W)))
    val rd_en = Vec(IssueWidth, Input(Bool()))
    val rd_paddr = Vec(IssueWidth, Input(UInt(log2Up(PrfSize).W)))
    val rd_data = Vec(IssueWidth, Input(UInt(64.W)))
    val flush = Input(Bool())
  })

  val prf = RegMem(PrfSize, UInt(64.W))

  for (i <- 0 until IssueWidth) {
    when (io.rd_en(i) && (io.rd_paddr(i) =/= 0.U)) {
      prf(io.rd_paddr(i)) := io.rd_data(i)
    }
  }

  val rs1_paddr = io.in.map(_.rs1_paddr)
  val rs2_paddr = io.in.map(_.rs2_paddr)
  val rs1_data = Wire(Vec(IssueWidth, UInt(64.W)))
  val rs2_data = Wire(Vec(IssueWidth, UInt(64.W)))

  for (i <- 0 until IssueWidth) {
    rs1_data(i) := Mux((rs1_paddr(i) =/= 0.U), prf(rs1_paddr(i)), 0.U)
    rs2_data(i) := Mux((rs2_paddr(i) =/= 0.U), prf(rs2_paddr(i)), 0.U)
  }

  // currently we only support execution pipe 0/1 bypassing
  for (i <- 0 until IssueWidth) {
    for (j <- 0 until IssueWidth - 1) {
      when (io.rd_en(j) && (io.rd_paddr(j) =/= 0.U)) {
        when (io.rd_paddr(j) === rs1_paddr(i)) {
          rs1_data(i) := io.rd_data(j)
        }
        when (io.rd_paddr(j) === rs2_paddr(i)) {
          rs2_data(i) := io.rd_data(j)
        }
      }
    }
  }

  when (reset.asBool) {
    for (i <- 0 until PrfSize) {
      prf(i.U) := 0.U
    }
  }

  // pipeline registers

  val out_uop = RegInit(VecInit(Seq.fill(IssueWidth)(0.U.asTypeOf(new MicroOp))))
  val out_rs1_data = RegInit(VecInit(Seq.fill(IssueWidth)(0.U(64.W))))
  val out_rs2_data = RegInit(VecInit(Seq.fill(IssueWidth)(0.U(64.W))))
  val out_valid = RegInit(false.B)

  when (io.flush) {
    for (i <- 0 until IssueWidth) {
      out_uop(i) := 0.U.asTypeOf(new MicroOp)
      out_uop(i).rs1_data := 0.U
      out_uop(i).rs2_data := 0.U
      out_rs1_data(i) := 0.U
      out_rs2_data(i) := 0.U
    }
  } .otherwise {
    for (i <- 0 until IssueWidth) {
      out_uop(i) := Mux(io.in(i).valid, io.in(i), 0.U.asTypeOf(new MicroOp))
      out_uop(i).rs1_data := rs1_data(i)
      out_uop(i).rs2_data := rs2_data(i)
      out_rs1_data(i) := rs1_data(i)
      out_rs2_data(i) := rs2_data(i)
    }
  }

  io.out := out_uop
  io.rs1_data := out_rs1_data
  io.rs2_data := out_rs2_data

  val arch_rename_table = WireInit(VecInit(Seq.fill(32)(0.U(log2Up(PrfSize).W))))
  BoringUtils.addSink(arch_rename_table, "arch_rename_table")

  if (EnableDifftest) {
    val rf_a0 = WireInit(0.U(64.W))
    BoringUtils.addSource(rf_a0, "rf_a0")

    val dt_ar = Module(new DifftestArchIntRegState)
    dt_ar.io.clock  := clock
    dt_ar.io.coreid := 0.U
    for (i <- 0 until 32) {
      dt_ar.io.gpr(i) := prf(arch_rename_table(i))
    }
    rf_a0 := dt_ar.io.gpr(10)
  }

  if(EnableFormal && CommitWidth <= 1) {

    val resultRegWire = Wire(Vec(32, UInt(64.W)))
    for(i <- 0 until 32) {
      resultRegWire(i) := prf(arch_rename_table(i))
    }
    ConnectCheckerResult.setRegSource(resultRegWire)
  }
}
