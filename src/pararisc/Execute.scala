package pararisc

import chisel3._
import chisel3.util._
import scala.language.reflectiveCalls

class Execute extends Module {
  // d 为decoder之后的具体信息，a，b为rs1，rs2中取出的数(不一定有效)，但是后续会将需要运算的两个数搬到a, b里
  // pc为当前指令的pc值, result为准备写进寄存器的值，next为下一跳 pc
  // d.pcA为a是否是io.pc，d.immB为b是否是d.imm
  // 若为条件跳转指令，a, b为rs1, rs2，需要对a, b做比较运算，同时计算出跳转pc，需要一周期内做两次并行运算，
  // 若为正常加减移位，则直接对a, b(或imm)做运算，结果放在result里面，然后next为pc + 4
  // 若为内存读写，则对a, imm做运算，结果放在addr里面，然后next为pc + 4
  // 若为直接跳转JAL，则对pc, imm做运算，结果放在next里面，result为pc + 4即将写进寄存器
  // 若为间接跳转JALR，则对rs1, imm做运算并清零最低位，结果放在next里面，result为pc + 4即将写进寄存器
  val io = IO(new Bundle {
    val d = Input(new Decoded)
    val pc = Input(UInt(32.W))
    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W))

    val result = Output(UInt(32.W))
    val next = Output(UInt(32.W))
    val taken = Output(Bool())
    val addr = Output(UInt(32.W))
  })

  val d = io.d

  val a = Mux(d.pcA, io.pc, io.a)
  val b = Mux(d.immB, d.imm, io.b)

  // 取反加 1，实现减法。
  val adder = Module(new Adder32)
  adder.io.a := a
  adder.io.b := Mux(d.sub, ~b, b) // 综合减法设计
  adder.io.cin := d.sub
  val add = adder.io.sum

  val shiftAmount = b(4, 0)

  // 按 sub 选择补 0 或补符号位，共用右移器。
  val rightInput = Cat(d.sub && a(31), a).asSInt
  val right = (rightInput >> shiftAmount).asUInt

  // 默认加减法
  val alu = WireDefault(add)
  switch(d.fn) {
    is(1.U) { // 左移
      alu := (a << shiftAmount)(31, 0)
    }
    is(2.U) { // 有符号比较
      alu := (a.asSInt < b.asSInt).asUInt
    }
    is(3.U) { // 无符号比较
      alu := (a < b).asUInt
    }
    is(4.U) { // 异或
      alu := a ^ b
    }
    is(5.U) { // 右移
      alu := right(31, 0)
    }
    is(6.U) { // 按位或
      alu := a | b
    }
    is(7.U) { // 按位与
      alu := a & b
    }
  }
  io.result := alu
  val pcPlus4 = io.pc + 4.U

  // 跳转保存返回地址，将写进寄存器，LUI 直接使用立即数。  
  when(d.jal || d.jalr) {
    io.result := pcPlus4
  }.elsewhen(d.lui) {
    io.result := d.imm
  }

  // 分支比较使用原始寄存器值。
  val signedLess = io.a.asSInt < io.b.asSInt
  val unsignedLess = io.a < io.b
  val cond = WireDefault(false.B)
  switch(d.fn) {
    is(0.U) { // 相等
      cond := io.a === io.b
    }
    is(1.U) { // 不相等
      cond := io.a =/= io.b
    }
    is(4.U) { // 有符号小于
      cond := signedLess
    }
    is(5.U) { // 有符号大于等于
      cond := !signedLess
    }
    is(6.U) { // 无符号小于
      cond := unsignedLess
    }
    is(7.U) { // 无符号大于等于
      cond := !unsignedLess
    }
  }

  io.taken := d.jal || d.jalr || (d.branch && cond) // 是否需要跳转

  // 基址加偏移，用于有效地址和 JALR 目标。
  val addressAdder = Module(new Adder32)
  addressAdder.io.a := io.a
  addressAdder.io.b := d.imm
  addressAdder.io.cin := false.B
  io.addr := addressAdder.io.sum

  // 分支和 JAL 的目标为 PC 加偏移。
  val targetAdder = Module(new Adder32)
  targetAdder.io.a := io.pc
  targetAdder.io.b := d.imm
  targetAdder.io.cin := false.B

  when(!io.taken) { // 返回下一条位置，用于判断分支预测是否正确，是否需要flush
    io.next := pcPlus4
  }.elsewhen(d.jalr) {
    io.next := Cat(io.addr(31, 1), 0.U(1.W))
  }.otherwise {
    io.next := targetAdder.io.sum
  }
}
