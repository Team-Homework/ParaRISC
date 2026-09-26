module address_generator_tb;
  logic clock = 0, reset = 0;
  logic [31:0] io_base, io_offset, io_storeData;
  logic [1:0] io_size;
  wire [31:0] io_address, io_wordAddress, io_shiftedStoreData;
  wire [1:0] io_byteLane;
  wire [3:0] io_writeMask;
  wire io_misaligned, io_sizeSupported;
  AddressGenerator dut (.*);

  task automatic check(input logic [31:0] base, offset, data,
                       input logic [1:0] size, input logic [31:0] addr,
                       input logic [3:0] mask, input logic [31:0] shifted,
                       input logic misaligned, supported);
    io_base = base; io_offset = offset; io_storeData = data; io_size = size;
    #1;
    if (io_address !== addr || io_wordAddress !== {addr[31:2], 2'b00} ||
        io_byteLane !== addr[1:0] || io_writeMask !== mask ||
        io_shiftedStoreData !== shifted || io_misaligned !== misaligned ||
        io_sizeSupported !== supported)
      $fatal(1, "AGU size=%d address=%h mask=%h data=%h misaligned=%b",
             size, io_address, io_writeMask, io_shiftedStoreData, io_misaligned);
  endtask

  initial begin
    check(32'h100, 1, 32'h000000cd, 0, 32'h101, 4'b0010, 32'h0000cd00, 0, 1);
    check(32'h100, 3, 32'h000000cd, 0, 32'h103, 4'b1000, 32'hcd000000, 0, 1);
    check(32'h100, 2, 32'h00005678, 1, 32'h102, 4'b1100, 32'h56780000, 0, 1);
    check(32'h100, 1, 32'h00005678, 1, 32'h101, 4'b0110, 32'h00567800, 1, 1);
    check(32'h100, 0, 32'hdeadbeef, 2, 32'h100, 4'b1111, 32'hdeadbeef, 0, 1);
    check(32'h100, 2, 32'hdeadbeef, 2, 32'h102, 4'b1111, 32'hbeef0000, 1, 1);
    check(0, 32'hffffffff, 0, 3, 32'hffffffff, 0, 0, 0, 0);
    $display("AddressGenerator: PASS");
    $finish;
  end
endmodule
