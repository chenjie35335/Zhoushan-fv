package zhoushan

import chisel3._
import chisel3.util._

class FpgaTop extends Module {
  val io = IO(new Bundle {
    val memAXI_0 = new AxiIO
  })

  val core = Module(new Core)

  val crossbar2to1 = Module(new CoreBusCrossbar2to1)
  crossbar2to1.io.in(0) <> core.io.imem
  crossbar2to1.io.in(1) <> core.io.dmem

  val simple2axi = Module(new CoreBus2Axi)
  simple2axi.in <> crossbar2to1.io.out
  simple2axi.out <> io.memAXI_0
}
