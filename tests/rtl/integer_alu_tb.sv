module integer_alu_tb;
  logic clock = 0;
  logic reset = 0;
  logic [31:0] io_a, io_b;
  logic [3:0] io_op;
  wire [31:0] io_result;
  wire io_equal, io_lessSigned, io_lessUnsigned;

  IntegerAlu dut (.*);

  task automatic check(input logic [31:0] a, b, expected, input logic [3:0] op);
    io_a = a;
    io_b = b;
    io_op = op;
    #1;
    if (io_result !== expected)
      $fatal(1, "op=%0d a=%h b=%h expected=%h actual=%h", op, a, b, expected, io_result);
  endtask

  initial begin
    check(32'hffffffff, 32'h1, 32'h0, 0);       // carry across all 32 bits
    check(32'h0, 32'h1, 32'hffffffff, 1);    // subtraction borrow
    check(32'h80000000, 32'h1, 32'h1, 2);   // signed less despite overflow
    check(32'h80000000, 32'h1, 32'h0, 3);   // unsigned greater
    check(32'h1, 32'hffffffff, 32'h1, 3);
    check(32'hf0f0f0f0, 32'h0f0f0f0f, 32'hffffffff, 4);
    check(32'hf0f0f0f0, 32'h0f0f0f0f, 32'hffffffff, 5);
    check(32'hf0f0f0f0, 32'h0f0f0f0f, 32'h00000000, 6);
    check(32'h1, 32'h1f, 32'h80000000, 7);
    check(32'h80000000, 32'h1f, 32'h1, 8);
    check(32'h80000000, 32'h1f, 32'hffffffff, 9);
    check(32'hdeadbeef, 32'h12345678, 32'h12345678, 10);
    repeat (1000) begin
      logic [31:0] a, b;
      a = $urandom;
      b = $urandom;
      check(a, b, a + b, 0);
      check(a, b, a - b, 1);
      check(a, b, {31'b0, ($signed(a) < $signed(b))}, 2);
      check(a, b, {31'b0, (a < b)}, 3);
    end
    $display("IntegerAlu: PASS");
    $finish;
  end
endmodule
