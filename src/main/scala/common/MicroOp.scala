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
import rvspeccore.checker.ConnectCheckerWb.MemSig
import zhoushan.Constant._

class MicroOp extends Bundle with ZhoushanConfig{
  val valid     = Bool()

  val valid_delay=Bool()

  val pc        = UInt(32.W)
  val npc       = UInt(32.W)
  val inst      = UInt(32.W)

  val fu_code   = UInt(FU_X.length.W)
  val alu_code  = UInt(ALU_X.length.W)
  val jmp_code  = UInt(JMP_X.length.W)
  val mem_code  = UInt(MEM_X.length.W)
  val mem_size  = UInt(MEM_X.length.W)
  val sys_code  = UInt(SYS_X.length.W)
  val w_type    = Bool()

  val rs1_src   = UInt(RS_X.length.W)
  val rs2_src   = UInt(RS_X.length.W)

  //verification rs1 rs2
  val rs1_data  = UInt(64.W)
  val rs2_data  = UInt(64.W)

  //verification memory
  val mem_info = new MemSig()(64)

  //verification CSR
  val csr_data  = new Csrs_Data
  val csr_wdata = UInt(64.W)
  val csr_addr  = UInt(12.W)
  val csr_wen   = Bool()

  val rs1_addr  = UInt(5.W)
  val rs2_addr  = UInt(5.W)
  val rd_addr   = UInt(5.W)
  val rd_en     = Bool()

  val imm       = UInt(32.W)

  // branch prediction related
  val pred_br   = Bool()
  val pred_bpc  = UInt(32.W)

  // register renaming related
  val rs1_paddr = UInt(log2Up(PrfSize).W)   // rs1 prf addr
  val rs2_paddr = UInt(log2Up(PrfSize).W)   // rs2 prf addr
  val rd_paddr  = UInt(log2Up(PrfSize).W)   // rd prf addr
  val rd_ppaddr = UInt(log2Up(PrfSize).W)   // rd prev prf addr

  val rd_data = UInt(64.W)

  // re-order buffer related
  val rob_addr  = UInt(log2Up(RobSize).W)

  def from_decoder(in: UInt, rd_addr: UInt): Unit = {
    val rd_en_tmp = WireInit(false.B)
    val imm_type = WireInit(0.U(IMM_X.length.W))
    val entries = Seq(valid, fu_code, alu_code, jmp_code, mem_code, mem_size,
                      sys_code, w_type, rs1_src, rs2_src, rd_en_tmp, imm_type)
    var i = 0
    for (entry <- entries.reverse) {
      val entry_width = entry.getWidth
      if (entry_width == 1) {
        entry := in(i)
      } else {
        entry := in(i + entry_width - 1, i)
      }
      i += entry_width
    }

    rd_en := rd_en_tmp && (rd_addr =/= 0.U)

    rs1_data := 0.U
    rs2_data := 0.U

    valid_delay := false.B

    mem_info := 0.U.asTypeOf(new MemSig()(64))

    csr_data := 0.U.asTypeOf(new Csrs_Data)

    val imm_i = Cat(Fill(21, inst(31)), inst(30, 20))
    val imm_s = Cat(Fill(21, inst(31)), inst(30, 25), inst(11, 7))
    val imm_b = Cat(Fill(20, inst(31)), inst(7), inst(30, 25), inst(11, 8), 0.U)
    val imm_u = Cat(inst(31, 12), Fill(12, 0.U))
    val imm_j = Cat(Fill(12, inst(31)), inst(19, 12), inst(20), inst(30, 21), 0.U)
    val imm_shamt = Mux(w_type, Cat(Fill(27, 0.U), inst(24, 20)), Cat(Fill(26, 0.U), inst(25, 20)))
    val imm_csr = Cat(Fill(27, 0.U), inst(19, 15))

    imm := MuxLookup(imm_type, 0.U(32.W), Array(
      s"b$IMM_I".U     -> imm_i,
      s"b$IMM_S".U     -> imm_s,
      s"b$IMM_B".U     -> imm_b,
      s"b$IMM_U".U     -> imm_u,
      s"b$IMM_J".U     -> imm_j,
      s"b$IMM_SHAMT".U -> imm_shamt,
      s"b$IMM_CSR".U   -> imm_csr
    ))
  }
}
