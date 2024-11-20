package formal 

import chisel3._
import chiseltest._
import chiseltest.formal._
import org.scalatest.flatspec.AnyFlatSpec
import zhoushan.{Alu, Core, Execution, Prf}
import zhoushan.Rename
import zhoushan.StallRegister
import zhoushan.Fence
import zhoushan.CacheController
import zhoushan.CacheBusWithUserIO
import zhoushan.Rob
import zhoushan.Meta
import zhoushan.Sram
import zhoushan.BranchPredictor
import zhoushan.Decode
import zhoushan.CacheBusCrossbar1to2
import zhoushan.CacheBusIO

class ZhoushanFormalSpec extends AnyFlatSpec with Formal with ChiselScalatestTester {
  behavior of "ZhoushanFormal"
  it should "pass" in {
    println("Begin Verification")
    // verify
    verify(new Core(), Seq(BoundedCheck(22), BtormcEngineAnnotation))
  }
}