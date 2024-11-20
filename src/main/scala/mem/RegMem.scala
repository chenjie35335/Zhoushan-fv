package zhoushan

import chisel3._
import chisel3.util._

class RegMem[T <: Data](size: Int, t: T) {
  val regs = RegInit(VecInit(Seq.fill(size)(0.U.asTypeOf(t))))

  def read(idx: UInt) = {
    regs(idx)
  }
  def write(idx: UInt, data: T) = {
    regs(idx) := data
  }
  def write(idx: UInt, data: T, mask: Seq[Bool]) = {
    val accessor = regs(idx).asInstanceOf[Vec[Data]]
    val dataVec  = data.asInstanceOf[Vec[Data]]
    require(accessor.length == dataVec.length)
    require(accessor.length == mask.length)
    for (i <- 0 until mask.length) {
      when(mask(i)) {
        accessor(i) := dataVec(i)
      }
    }
  }

  def apply(idx: UInt) = read(idx)
}

object RegMem {
  def apply[T <: Data](size: Int, t: T) = new RegMem(size, t)
}

class SyncRegMem[T <: Data](size: Int, t: T) extends RegMem(size, t) {
  override def read(idx: UInt) = {
    RegNext(regs(idx))
  }
  def read(idx: UInt, en: Bool) = {
    RegEnable(regs(idx), en)
  }
}

object SyncRegMem {
  def apply[T <: Data](size: Int, t: T) = new SyncRegMem(size, t)
}

// class WriteFirstSyncRegMem[T <: Data](size: Int, t: T) extends RegMem(size, t) {
//   private val writeEn = Wire(Bool())
//   private val writeIdx = Wire(UInt(log2Ceil(size).W))
//   private val writeData = Wire(t)

//   writeEn := false.B
//   writeIdx := 0.U
//   writeData := 0.U.asTypeOf(t)
//   // 读取逻辑
//   override def read(idx: UInt): T = {
//     val readData = RegNext(regs(idx))
//     Mux(RegNext(writeEn && writeIdx === idx), RegNext(writeData), readData)
//   }//这里存在问题，因为这里不止一个端口写，所有的端口写都要考虑的

//   def read(idx: UInt, en: Bool): T = {
//     val readData = RegEnable(regs(idx), en)
//     Mux(RegNext(writeEn && writeIdx === idx && en), RegNext(writeData), readData)
//   }

//   // 写入逻辑
//   override def write(idx: UInt, data: T): Unit = {
//     writeEn := true.B
//     writeIdx := idx
//     writeData := data
//     super.write(idx, data)
//   }

//   // 带掩码的写入逻辑
//   override def write(idx: UInt, data: T, mask: Seq[Bool]): Unit = {
//     writeEn := true.B
//     writeIdx := idx
//     writeData := data
//     super.write(idx, data, mask)
//   }
// }

// object WriteFirstSyncRegMem {
//   def apply[T <: Data](size: Int, t: T) = new WriteFirstSyncRegMem(size, t)
// }

class WriteFirstSyncRegMem[T <: Data](size: Int, t: T) extends RegMem(size, t) {
  private val writeEn = Wire(Vec(size, Bool()))
  private val writeIdx = Wire(Vec(size, UInt(log2Ceil(size).W)))
  private val writeData = Wire(Vec(size, t))

  // 初始化
  for (i <- 0 until size) {
    writeEn(i) := false.B
    writeIdx(i) := 0.U
    writeData(i) := 0.U.asTypeOf(t)
  }

  // 读取逻辑
  override def read(idx: UInt): T = {
    val readData = RegNext(regs(idx),0.U.asTypeOf(t))
    val writeEnable = writeEn.zipWithIndex.map { case (en, i) => en && writeIdx(i) === idx }.reduce(_ || _)
    Mux(RegNext(writeEnable), RegNext(writeData(idx)), readData)
  }

  def read(idx: UInt, en: Bool): T = {
    val readData = RegEnable(regs(idx), en)
    val writeEnable = writeEn.zipWithIndex.map { case (en, i) => en && writeIdx(i) === idx && en }.reduce(_ || _)
    Mux(RegNext(writeEnable), RegNext(writeData(idx)), readData)
  }

  // 写入逻辑
  override def write(idx: UInt, data: T): Unit = {
    writeEn(idx.asUInt) := true.B
    writeIdx(idx.asUInt) := idx
    writeData(idx.asUInt) := data
    super.write(idx, data)
  }

  // 带掩码的写入逻辑
  override def write(idx: UInt, data: T, mask: Seq[Bool]): Unit = {
    writeEn(idx.asUInt) := true.B
    writeIdx(idx.asUInt) := idx
    writeData(idx.asUInt) := data
    super.write(idx, data, mask)
  }
}

object WriteFirstSyncRegMem {
  def apply[T <: Data](size: Int, t: T) = new WriteFirstSyncRegMem(size, t)
}
