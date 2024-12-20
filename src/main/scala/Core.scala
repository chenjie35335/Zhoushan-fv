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
import rvspeccore.checker._
import rvspeccore.checker.CheckerWithResult
import rvspeccore.checker.ConnectCheckerResult

class Core extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val core_bus = Vec(4, new CoreBusIO)
  })

  val flush = WireInit(false.B)

  /* ----- Stage 1 - Instruction Fetch (IF) ------ */

  val fetch = Module(new InstFetch)

  val icache = Module(new CacheController(new CacheBusWithUserIO, InstCacheId, InstUncacheId))
  icache.io.in <> fetch.io.imem
  icache.io.out_cache <> io.core_bus(InstCacheId - 1)
  icache.io.out_uncache <> io.core_bus(InstUncacheId - 1)

  /* ----- Stage 2 - Instruction Buffer (IB) ----- */

  val ibuf = Module(new InstBuffer)
  ibuf.io.in <> fetch.io.out
  ibuf.io.flush := flush

  if(EnableFormal) {
    val InstVec = ibuf.io.out.bits.vec.map(_.inst)
    implicit val checker_xlen = 64
    when(ibuf.io.out.valid) {
      for(i <- 0 until InstVec.size){
        assume(
            RVI.regImm(InstVec(i)) || RVI.regReg(InstVec(i)) ||
            RVI.control(InstVec(i)) || //RVI.loadStore(InstVec(i)) ||
              RVZicsr.reg(InstVec(i)) || RVZicsr.imm(InstVec(i))
        )
      }
  }
  }

  /* ----- Stage 3 - Instruction Decode (ID) ----- */

  val decode = Module(new Decode)
  decode.io.in <> ibuf.io.out
  decode.io.flush := flush

  val rename = Module(new Rename)
  rename.io.in <> decode.io.out
  rename.io.flush := flush

  /* ----- Stage 4 - Issue (IS) ------------------ */

  val stall_reg = Module(new StallRegister)
  stall_reg.io.in <> rename.io.out
  stall_reg.io.flush := flush

  val rob = Module(new Rob)
  val isu = Module(new IssueUnit)

  rob.io.in.bits := stall_reg.io.out.bits
  rob.io.in.valid := stall_reg.io.out.valid && isu.io.in.ready
  rob.io.flush := flush

  rename.io.cm_recover := RegNext(rob.io.jmp_packet.mis)
  rename.io.cm := rob.io.cm

  fetch.io.jmp_packet := rob.io.jmp_packet
  flush := rob.io.jmp_packet.mis

  isu.io.in.bits := stall_reg.io.out.bits
  isu.io.in.valid := stall_reg.io.out.valid && rob.io.in.ready
  isu.io.rob_addr := rob.io.rob_addr
  isu.io.flush := flush
  isu.io.avail_list := rename.io.avail_list
  isu.io.sys_ready := rob.io.sys_ready

  stall_reg.io.out.ready := rob.io.in.ready && isu.io.in.ready

  /* ----- Stage 5 - Register File (RF) ---------- */

  val rf = Module(new Prf)
  rf.io.in := isu.io.out
  rf.io.flush := flush

  /* ----- Stage 6 - Execution (EX) -------------- */

  val execution = Module(new Execution)
  execution.io.in <> rf.io.out
  execution.io.flush := flush
  execution.io.rs1_data := rf.io.rs1_data
  execution.io.rs2_data := rf.io.rs2_data

  for (i <- 0 until IssueWidth) {
    if (i < IssueWidth - 1) {
      rename.io.exe(i) := isu.io.out(i)
    } else {
      rename.io.exe(i) := execution.io.lsu_wakeup_uop
    }
  }

  rob.io.exe := execution.io.out
  rob.io.exe_ecp := execution.io.out_ecp

  isu.io.lsu_ready := execution.io.lsu_ready

  val sq = Module(new StoreQueue)
  sq.io.flush := flush
  sq.io.in_st <> execution.io.dmem_st
  sq.io.in_ld <> execution.io.dmem_ld
  sq.io.deq_req := rob.io.sq_deq_req

  val crossbar2to1 = Module(new CacheBusCrossbarNto1(new CacheBusReq, new CacheBusIO, 2))
  crossbar2to1.io.in(0) <> sq.io.out_st
  crossbar2to1.io.in(1) <> sq.io.out_ld

  val crossbar1to2 = Module(new CacheBusCrossbar1to2(new CacheBusIO))
  crossbar1to2.io.in <> crossbar2to1.io.out

  val cb1to2_addr = crossbar1to2.io.in.req.bits.addr
  val cb1to2_to_clint = (cb1to2_addr >= ClintAddrBase.U && cb1to2_addr < ClintAddrBase.U + ClintAddrSize.U)
  crossbar1to2.io.to_1 := cb1to2_to_clint

  val dcache = Module(new CacheController(new CacheBusIO, DataCacheId, DataUncacheId))
  dcache.io.in <> crossbar1to2.io.out(0)
  dcache.io.out_cache <> io.core_bus(DataCacheId - 1)
  dcache.io.out_uncache <> io.core_bus(DataUncacheId - 1)

  val clint = Module(new Clint)
  clint.io.in <> crossbar1to2.io.out(1)

  /* ----- Stage 7 - Commit (CM) ----------------- */

  rf.io.rd_en := execution.io.rd_en
  rf.io.rd_paddr := execution.io.rd_paddr
  rf.io.rd_data := execution.io.rd_data

  val cm = rob.io.cm
  val cm_rd_data = rob.io.cm_rd_data
  val cm_mmio = rob.io.cm_mmio

  /* ----- CSR & Difftest ------------------------ */

  val cycle_cnt = RegInit(0.U(64.W))
  val instr_cnt = RegInit(0.U(64.W))

  BoringUtils.addSource(cycle_cnt, "csr_mcycle")
  BoringUtils.addSource(instr_cnt, "csr_minstret")

  cycle_cnt := cycle_cnt + 1.U
  instr_cnt := instr_cnt + PopCount(cm.map(_.valid))

  if (EnableDifftest) {
    val rf_a0 = WireInit(0.U(64.W))
    BoringUtils.addSink(rf_a0, "rf_a0")

    for (i <- 0 until CommitWidth) {
      val skip = (cm(i).inst === Instructions.PUTCH) ||
                 (cm(i).fu_code === s"b${Constant.FU_SYS}".U && cm(i).inst(31, 20) === Csrs.mcycle) ||
                 cm_mmio(i)

      val dt_ic = Module(new DifftestInstrCommit)
      dt_ic.io.clock    := clock
      dt_ic.io.coreid   := 0.U
      dt_ic.io.index    := i.U
      dt_ic.io.valid    := RegNext(cm(i).valid)
      dt_ic.io.pc       := RegNext(cm(i).pc)
      dt_ic.io.instr    := RegNext(cm(i).inst)
      dt_ic.io.skip     := RegNext(skip)
      dt_ic.io.isRVC    := false.B
      dt_ic.io.scFailed := false.B
      dt_ic.io.wen      := RegNext(cm(i).rd_en)
      dt_ic.io.wdata    := RegNext(cm_rd_data(i))
      dt_ic.io.wdest    := RegNext(cm(i).rd_addr)
      dt_ic.io.special  := 0.U

      when (dt_ic.io.valid && dt_ic.io.instr === Instructions.PUTCH) {
        printf("%c", rf_a0(7, 0))
      }

      if (DebugCommit) {
        val u = cm(i)
        when (u.valid) {
          printf("%d: [CM %d ] pc=%x inst=%x rs1=%d->%d rs2=%d->%d rd(en=%x)=%d->%d bp(br=%x bpc=%x) rob=%d wdata=%x\n", DebugTimer(), i.U,
                 u.pc, u.inst, u.rs1_addr, u.rs1_paddr, u.rs2_addr, u.rs2_paddr, u.rd_en, u.rd_addr, u.rd_paddr, u.pred_br, u.pred_bpc, u.rob_addr, cm_rd_data(i))
        }
      }

      if (DebugCommitSimple) {
        val u = cm(i)
        when (u.valid) {
          printf("%x\n", u.pc)
        }
      }
    }

    val trap = Cat(cm.map(_.inst === "h0000006b".U).reverse) & Cat(cm.map(_.valid).reverse)
    val trap_idx = OHToUInt(trap)

    val dt_te = Module(new DifftestTrapEvent)
    dt_te.io.clock    := clock
    dt_te.io.coreid   := 0.U
    dt_te.io.valid    := RegNext(trap.orR)
    dt_te.io.code     := RegNext(rf_a0(2, 0))
    dt_te.io.pc       := 0.U
    for (i <- 0 until CommitWidth) {
      when (trap_idx === i.U) {
        dt_te.io.pc   := RegNext(cm(i).pc)
      }
    }
    dt_te.io.cycleCnt := cycle_cnt
    dt_te.io.instrCnt := instr_cnt

    if (EnableMisRateCounter) {
      val profile_jmp_counter = WireInit(UInt(64.W), 0.U)
      val profile_mis_counter = WireInit(UInt(64.W), 0.U)
      BoringUtils.addSink(profile_jmp_counter, "profile_jmp_counter")
      BoringUtils.addSink(profile_mis_counter, "profile_mis_counter")
      when (dt_te.io.valid) {
        printf("Jump: %d, Mis: %d\n", profile_jmp_counter, profile_mis_counter)
      }
    }

    val dt_cs = Module(new DifftestCSRState)
    dt_cs.io.clock          := clock
    dt_cs.io.coreid         := 0.U
    dt_cs.io.priviledgeMode := 3.U  // Machine mode
    dt_cs.io.mstatus        := 0.U
    dt_cs.io.sstatus        := 0.U
    dt_cs.io.mepc           := 0.U
    dt_cs.io.sepc           := 0.U
    dt_cs.io.mtval          := 0.U
    dt_cs.io.stval          := 0.U
    dt_cs.io.mtvec          := 0.U
    dt_cs.io.stvec          := 0.U
    dt_cs.io.mcause         := 0.U
    dt_cs.io.scause         := 0.U
    dt_cs.io.satp           := 0.U
    dt_cs.io.mip            := 0.U
    dt_cs.io.mie            := 0.U
    dt_cs.io.mscratch       := 0.U
    dt_cs.io.sscratch       := 0.U
    dt_cs.io.mideleg        := 0.U
    dt_cs.io.medeleg        := 0.U
  }

  if(EnableFormal) {
    val XLEN = 64
    if(CommitWidth <= 1) {
      val checker = Module(new CheckerWithResult(checkMem = false,enableReg = true)(ZhoushanConfig.FormalConfig))
      // val skip = (cm(0).inst === Instructions.PUTCH) ||
      //                 (cm(0).fu_code === s"b${Constant.FU_SYS}".U && cm(0).inst(31, 20) === Csrs.mcycle) ||
      //                 cm_mmio(0)
      checker.io.instCommit.valid := RegNext(cm(0).valid,false.B)
      checker.io.instCommit.pc := RegNext(ZeroExt32_64(cm(0).pc),0.U(64.W))
      checker.io.instCommit.inst := RegNext(cm(0).inst,0.U(64.W))

      ConnectCheckerResult.setChecker(checker)(XLEN, ZhoushanConfig.FormalConfig)
    } else {
      val checker = Module(new CheckerWithWB(checkMem = false, enableReg = true)(ZhoushanConfig.FormalConfig))
      val sel = RegEnable(DontCare,0.U(log2Ceil(CommitWidth).W),cm(0).valid || cm(1).valid)

      val SelPack = MuxCase(0.U.asTypeOf(new MicroOp), Array(
        (sel === 0.U) -> cm(0),
        (sel === 1.U) -> cm(1),
      ))
      val SelRdData = MuxCase(0.U, Array(
        (sel === 0.U) -> cm_rd_data(0),
        (sel === 1.U) -> cm_rd_data(1),
      ))

      checker.io.instCommit.valid := RegNext(SelPack.valid,false.B)
      checker.io.instCommit.pc    := RegNext(SelPack.pc,0.U(XLEN.W))
      checker.io.instCommit.inst  := RegNext(SelPack.inst, 0.U(32.W))
      checker.io.instCommit.npc   := RegNext(Cat(SelPack.npc(31,2),Fill(2,0.U)),0.U(XLEN.W))

      checker.io.wb.data   := RegNext(SelRdData,0.U(XLEN.W))
      checker.io.wb.dest   := RegNext(SelPack.rd_addr,0.U(5.W))
      checker.io.wb.valid  := RegNext(SelPack.rd_en,false.B)
      checker.io.wb.r1Addr := RegNext(SelPack.rs1_addr,0.U(5.W))
      checker.io.wb.r2Addr := RegNext(SelPack.rs2_addr,0.U(5.W))
      checker.io.wb.r2Addr := RegNext(SelPack.rs2_addr,0.U(5.W))
      checker.io.wb.r1Data := RegNext(SelPack.rs1_data,0.U(XLEN.W))
      checker.io.wb.r2Data := RegNext(SelPack.rs2_data,0.U(XLEN.W))
      checker.io.wb.csrAddr:= RegNext(SelPack.csr_addr,0.U)
      checker.io.wb.csrWr  := RegNext(SelPack.csr_wen,false.B)
      checker.io.wb.csrNdata := RegNext(SelPack.csr_wdata,0.U)
      //checker.io.wb.csrData := RegNext(SelPack.csr_data,0.U)
      ConnectCheckerWb.setChecker(checker)(64,ZhoushanConfig.FormalConfig)

      val mem = ConnectCheckerWb.makeMemSource()(64)
      mem.write.valid     := RegNext(SelPack.mem_info.write.valid,false.B)
      mem.write.addr      := RegNext(ZeroExt32_64(SelPack.mem_info.write.addr),0.U)
      mem.write.data      := RegNext(SelPack.mem_info.write.data,0.U)
      mem.write.memWidth  := RegNext(SelPack.mem_info.write.memWidth,0.U)
      mem.read.valid      := RegNext(SelPack.mem_info.read.valid,false.B)
      mem.read.addr       := RegNext(ZeroExt32_64(SelPack.mem_info.read.addr),0.U)
      mem.read.data       := RegNext(SelPack.mem_info.read.data,0.U)
      mem.read.memWidth   := RegNext(SelPack.mem_info.read.memWidth,0.U)

      val csr = ConnectCheckerWb.makeCSRSource()(XLEN = 64,ZhoushanConfig.FormalConfig)
      csr.mhartid := RegNext(SelPack.csr_data.mhartid ,0.U)
      csr.mstatus := RegNext(SelPack.csr_data.mstatus ,0.U)
      csr.mie     := RegNext(SelPack.csr_data.mie     ,0.U)
      csr.mtvec   := RegNext(SelPack.csr_data.mtvec   ,0.U)
      csr.mscratch:= RegNext(SelPack.csr_data.mscratch,0.U)
      csr.mepc    := RegNext(SelPack.csr_data.mepc    ,0.U)
      csr.mcause  := RegNext(SelPack.csr_data.mcause  ,0.U)
    }
  }

  if (EnableQueueAnalyzer) {
    val profile_queue_ib_count     = WireInit(UInt(8.W), 0.U)
    val profile_queue_iq_int_count = WireInit(UInt(8.W), 0.U)
    val profile_queue_iq_mem_count = WireInit(UInt(8.W), 0.U)
    val profile_queue_rob_count    = WireInit(UInt(8.W), 0.U)
    val profile_queue_sq_count     = WireInit(UInt(8.W), 0.U)

    BoringUtils.addSink(profile_queue_ib_count,     "profile_queue_ib_count")
    BoringUtils.addSink(profile_queue_iq_int_count, "profile_queue_iq_int_count")
    BoringUtils.addSink(profile_queue_iq_mem_count, "profile_queue_iq_mem_count")
    BoringUtils.addSink(profile_queue_rob_count,    "profile_queue_rob_count")
    BoringUtils.addSink(profile_queue_sq_count,     "profile_queue_sq_count")

    printf("%d: [QUEUE] ib=%d iq_int=%d iq_mem=%d rob=%d sq=%d\n", DebugTimer(),
           profile_queue_ib_count, profile_queue_iq_int_count, profile_queue_iq_mem_count,
           profile_queue_rob_count, profile_queue_sq_count)
  }

}
