module branch_unit_tb;
  logic clock = 0, reset = 0;
  logic [31:0] io_pc, io_a, io_b, io_offset;
  logic [3:0] io_op;
  wire io_taken, io_targetMisaligned;
  wire [31:0] io_nextPc, io_link;
  BranchUnit dut (.*);

  task automatic check(input logic [3:0] op, input logic [31:0] pc, a, b, offset,
                       input logic taken, input logic [31:0] nextPc,
                       input logic misaligned);
    io_op = op; io_pc = pc; io_a = a; io_b = b; io_offset = offset;
    #1;
    if (io_taken !== taken || io_nextPc !== nextPc ||
        io_link !== pc + 32'd4 || io_targetMisaligned !== misaligned)
      $fatal(1, "branch op=%0d pc=%h got taken=%b next=%h link=%h align=%b",
             op, pc, io_taken, io_nextPc, io_link, io_targetMisaligned);
  endtask

  initial begin
    check(1, 32'h100, 5, 5, 8, 1, 32'h108, 0);             // BEQ
    check(1, 32'h100, 5, 6, 8, 0, 32'h104, 0);
    check(2, 32'h100, 5, 6, 8, 1, 32'h108, 0);             // BNE
    check(3, 32'h100, 32'h80000000, 1, 8, 1, 32'h108, 0); // BLT signed
    check(4, 32'h100, 32'h80000000, 1, 8, 0, 32'h104, 0); // BGE signed
    check(5, 32'h100, 32'h80000000, 1, 8, 0, 32'h104, 0); // BLTU
    check(6, 32'h100, 32'h80000000, 1, 8, 1, 32'h108, 0); // BGEU
    check(7, 32'h100, 0, 0, 32'hfffffffc, 1, 32'hfc, 0);  // JAL -4
    check(8, 32'h100, 32'h101, 0, 4, 1, 32'h104, 0);     // JALR clears bit 0
    check(8, 32'h100, 32'h102, 0, 4, 1, 32'h106, 1);     // still misaligned
    check(0, 32'hfffffffc, 0, 0, 0, 0, 0, 0);             // PC+4 wraps
    $display("BranchUnit: PASS");
    $finish;
  end
endmodule
