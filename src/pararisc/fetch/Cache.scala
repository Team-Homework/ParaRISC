package pararisc.fetch
import chisel3._
import chisel3.util._
import chisel3.experimental.{annotate,ChiselAnnotation}

/** Synchronous tag and data SRAMs with per-word validity.
  * One-cycle load hits, pipelined word refills, buffered write-through stores.
  * Store allocation preserves written sectors without fetching unused words.
  */
class Cache(bytes: Int, ways: Int, lineBytes: Int, writeEntries: Int = 16, readonly: Boolean = false, readAhead: Boolean = false, preserveHierarchy: Boolean = false) extends Module {
  if(preserveHierarchy) {
    val owner = this
    annotate(new ChiselAnnotation {
      override def toFirrtl: firrtl.annotations.Annotation =
        firrtl.AttributeAnnotation(owner.toTarget,"keep_hierarchy = 1")
    })
  }
  val io = IO(new Bundle { val cpu = Flipped(new MemPort); val mem = new MemPort; val fill = Valid(new MemReq); val loadReady = Output(Bool()) })
  val sets = bytes / ways / lineBytes
  val ob = log2Ceil(lineBytes); val sb = log2Ceil(sets); val wb = ob-2
  val tagWidth = 32-ob-sb; val lineWords = lineBytes/4
  val valid = RegInit(VecInit(Seq.fill(ways)(VecInit(Seq.fill(sets)(false.B)))))
  val replace = RegInit(VecInit(Seq.fill(sets)(false.B)))
  val dataRam = Seq.fill(ways)(Module(new Sram(bytes/ways/4)))
  val tagRamWidth = tagWidth + (if(readonly) 0 else lineWords)
  val tagRam = Seq.fill(ways)(Module(new Sram(sets,tagRamWidth,tagRamWidth)))
  val idle :: check :: refill :: retry :: Nil = Enum(4)
  val state = RegInit(idle)
  // Instruction requests carry only an address. Keeping unused store fields
  // across this hierarchy would otherwise retain registers and write queues.
  val requestWord = Module(new LocalWord(if(readonly) 32 else (new MemReq).getWidth))
  val request = Wire(new MemReq)
  if(readonly) { request := 0.U.asTypeOf(new MemReq); request.addr := requestWord.io.value }
  else { request := requestWord.io.value.asTypeOf(new MemReq) }
  val victim = Reg(UInt(math.max(1,log2Ceil(ways)).W))
  val sent = RegInit(0.U((wb+1).W)); val received = RegInit(0.U((wb+1).W))
  val earlyReturned = RegInit(false.B)
  val writeEnq = Wire(Decoupled(new MemReq)); val writeDeq = Wire(Decoupled(new MemReq))
  if(readonly) { writeEnq.ready := true.B; writeDeq.valid := false.B; writeDeq.bits := 0.U.asTypeOf(new MemReq) }
  else { val writes = Module(new PreparedQueue(new MemReq,writeEntries)); writes.io.enq <> writeEnq; writeDeq <> writes.io.deq }
  val direction = Module(new Queue(Bool(),16))
  val index = request.addr(ob+sb-1,ob); val tag = request.addr(31,ob+sb); val word = request.addr(ob-1,2)
  // The course SRAM makes rdata undefined on disabled/write cycles.
  // Preserve only a completed read, with a direct first-cycle bypass so
  // pipelined hits retain their original latency.
  def readResult(ram: Sram): UInt = {
    if(!readAhead) ram.io.rdata
    else {
      val completedRead = RegNext(ram.io.en && !ram.io.we,false.B)
      val held = RegEnable(ram.io.rdata,completedRead)
      Mux(completedRead,ram.io.rdata,held)
    }
  }
  val tagRead = tagRam.map(readResult)
  val dataRead = dataRam.map(readResult)
  val tagHits = VecInit((0 until ways).map(w => valid(w)(index) && (if(readonly) tagRead(w) else tagRead(w)(tagRamWidth-1,lineWords)) === tag)).asUInt
  val hits = if(readonly) tagHits else VecInit((0 until ways).map(w => tagHits(w) && tagRead(w)(lineWords-1,0)(word))).asUInt
  val hit = hits.orR
  val earlyResponse = state === refill && !earlyReturned && direction.io.deq.valid && !direction.io.deq.bits && received === 0.U
  io.cpu.rsp.valid := (state === check && !request.write && hit) || (earlyResponse && io.mem.rsp.valid)
  io.cpu.rsp.bits.data := Mux1H((0 until ways).map(w => hits(w) -> dataRead(w)))
  io.cpu.rsp.bits.error := false.B
  when(earlyResponse) { io.cpu.rsp.bits.data := io.mem.rsp.bits.data }
  io.loadReady := state === idle || (state === check && io.cpu.rsp.fire)
  requestWord.io.prepare := io.loadReady; requestWord.io.data := (if(readonly) io.cpu.req.bits.addr else io.cpu.req.bits.asUInt)
  io.cpu.req.ready := io.loadReady && (if(readonly) true.B else !io.cpu.req.bits.write || writeEnq.ready)
  writeEnq.valid := io.cpu.req.fire && io.cpu.req.bits.write; writeEnq.bits := io.cpu.req.bits
  val fillReq = state === refill && sent < lineWords.U
  io.mem.req.valid := direction.io.enq.ready && (writeDeq.valid || fillReq)
  io.mem.req.bits := writeDeq.bits
  when(!writeDeq.valid) {
    io.mem.req.bits.addr := Cat(request.addr(31,ob),(word+sent)(wb-1,0),0.U(2.W))
    io.mem.req.bits.data := 0.U; io.mem.req.bits.mask := 0.U; io.mem.req.bits.write := false.B
  }
  writeDeq.ready := io.mem.req.ready && direction.io.enq.ready
  direction.io.enq.valid := io.mem.req.fire; direction.io.enq.bits := io.mem.req.bits.write
  io.mem.rsp.ready := direction.io.deq.valid && (!earlyResponse || io.cpu.rsp.ready); direction.io.deq.ready := io.mem.rsp.fire
  val filling = io.mem.rsp.fire && !direction.io.deq.bits
  io.fill.valid := (filling && !io.mem.rsp.bits.error) || (state === check && io.cpu.rsp.fire)
  io.fill.bits := 0.U.asTypeOf(new MemReq)
  io.fill.bits.addr := Mux(filling,Cat(request.addr(31,ob),(word+received)(wb-1,0),0.U(2.W)),request.addr)
  io.fill.bits.data := Mux(filling,io.mem.rsp.bits.data,io.cpu.rsp.bits.data)
  val lastFill = filling && received === (lineWords-1).U
  val storing = state === check && request.write
  val canStore = request.mask.andR || tagHits.orR
  val newWay = if(ways==1) 0.U else Mux(!valid(0)(index),0.U,Mux(!valid(1)(index),1.U,replace(index).asUInt))
  val storeWay = if(ways==1) 0.U else Mux(tagHits.orR,PriorityEncoder(tagHits),newWay)
  when(io.mem.req.fire && !io.mem.req.bits.write) { sent := sent+1.U }
  when(io.mem.rsp.fire) { assert(!io.mem.rsp.bits.error,"AXI memory response error") }
  for(w <- 0 until ways) {
    val d = dataRam(w).io; val t = tagRam(w).io
    d.clk := clock; t.clk := clock
    // Explicit read-result registers preserve stalled checks. Prepare the
    // next candidate address independently of admission during idle cycles.
    val reading = if(readAhead) state === idle || state === retry || (state === check && io.cpu.rsp.fire)
      else io.cpu.req.fire || state === retry || (state === check && !request.write && !io.cpu.rsp.fire)
    val readAddr = if(readAhead) Mux(state === retry,request.addr,io.cpu.req.bits.addr)
      else Mux(io.loadReady,io.cpu.req.bits.addr,request.addr)
    d.en := reading || (filling && victim === w.U) || (storing && canStore && storeWay === w.U)
    d.we := (filling && victim === w.U) || (storing && canStore && storeWay === w.U)
    d.addr := Mux(storing,request.addr(ob+sb-1,2),readAddr(ob+sb-1,2)); d.wdata := request.data; d.wmask := request.mask
    when(filling) { d.addr := Cat(index,(word+received)(wb-1,0)); d.wdata := io.mem.rsp.bits.data; d.wmask := 15.U }
    t.en := reading || (lastFill && victim === w.U) || (storing && canStore && storeWay === w.U)
    t.we := (lastFill && victim === w.U) || (storing && canStore && storeWay === w.U)
    t.addr := Mux(t.we,index,readAddr(ob+sb-1,ob)); t.wmask := 1.U
    if(readonly) { t.wdata := tag }
    else {
      val sectors = Mux(tagHits(w),tagRead(w)(lineWords-1,0),0.U) | Mux(request.mask.andR,UIntToOH(word,lineWords),0.U)
      t.wdata := Cat(tag,Mux(lastFill,Fill(lineWords,1.U(1.W)),sectors))
    }
    for(s <- 0 until sets) {
      when(lastFill && victim === w.U && index === s.U) { valid(w)(s) := true.B }
      when(storing && canStore && storeWay === w.U && index === s.U) {
        valid(w)(s) := true.B
      }
    }
  }
  when(io.cpu.rsp.fire && state === check) { state := idle }
  when(io.cpu.rsp.fire && earlyResponse) { earlyReturned := true.B }
  when(io.cpu.req.fire) { state := check }
  when(storing) { state := idle; if(ways==2) { replace(index) := !storeWay.asBool } }
  when(state === check && !request.write && !hit) { state := refill; sent := 0.U; received := 0.U; earlyReturned := false.B; victim := Mux(tagHits.orR,PriorityEncoder(tagHits),newWay) }
  when(filling) { received := received+1.U; when(lastFill) { state := Mux(earlyReturned || io.cpu.rsp.fire,idle,retry); if(ways==2) { replace(index) := !victim.asBool } } }
  when(state === retry) { state := check }
}
