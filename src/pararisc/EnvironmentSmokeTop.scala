package pararisc

import chisel3._
import chisel3.util._

/** Environment check only. Reads RAM[0] and returns it through the exit MMIO.
  * This is not a CPU and is emitted only with Generate --smoke.
  */
class EnvironmentSmokeTop extends CourseTop {
  withClockAndReset(clock, reset) {
    val fetchAddress :: fetchData :: store :: done :: Nil = Enum(4)
    val state = RegInit(fetchAddress)
    val result = RegInit(0.U(32.W))
    val addressSent = RegInit(false.B)
    val dataSent = RegInit(false.B)

    araddr := 0.U
    arvalid := state === fetchAddress
    rready := state === fetchData
    awaddr := "h80000000".U
    awvalid := state === store && !addressSent
    wdata := result
    wstrb := "hf".U
    wvalid := state === store && !dataSent
    bready := true.B

    when(state === fetchAddress && arready) {
      state := fetchData
    }
    when(state === fetchData && rvalid) {
      result := rdata
      state := store
    }
    when(state === store) {
      when(awready) { addressSent := true.B }
      when(wready) { dataSent := true.B }
      when((addressSent || awready) && (dataSent || wready)) {
        state := done
      }
    }
  }
}
