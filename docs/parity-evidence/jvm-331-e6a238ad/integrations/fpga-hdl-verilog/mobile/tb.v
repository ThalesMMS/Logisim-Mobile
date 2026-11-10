module tb;
  reg s_fpga_p0_b0 = 0;
  wire s_fpga_p1_b0;
  reg s_fpga_p2_b0 = 0;
  reg s_fpga_p3_b0 = 0;
  reg s_fpga_p4_b0 = 0;
  wire s_fpga_p5_b0;
  wire s_fpga_p6_b0;
  logisim_fpga_top dut(.fpga_p0_b0(s_fpga_p0_b0), .fpga_p1_b0(s_fpga_p1_b0), .fpga_p2_b0(s_fpga_p2_b0), .fpga_p3_b0(s_fpga_p3_b0), .fpga_p4_b0(s_fpga_p4_b0), .fpga_p5_b0(s_fpga_p5_b0), .fpga_p6_b0(s_fpga_p6_b0));
  initial begin
    s_fpga_p0_b0 = 0;
    s_fpga_p2_b0 = 0;
    s_fpga_p3_b0 = 0;
    s_fpga_p4_b0 = 0;
    repeat (8) begin #5; end
    $display("ROW 0000 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    s_fpga_p0_b0 = 0;
    s_fpga_p2_b0 = 0;
    s_fpga_p3_b0 = 0;
    s_fpga_p4_b0 = 1;
    repeat (8) begin #5; end
    $display("ROW 0001 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    s_fpga_p0_b0 = 0;
    s_fpga_p2_b0 = 0;
    s_fpga_p3_b0 = 1;
    s_fpga_p4_b0 = 0;
    repeat (8) begin #5; end
    $display("ROW 0010 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    s_fpga_p0_b0 = 0;
    s_fpga_p2_b0 = 0;
    s_fpga_p3_b0 = 1;
    s_fpga_p4_b0 = 1;
    repeat (8) begin #5; end
    $display("ROW 0011 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    s_fpga_p0_b0 = 0;
    s_fpga_p2_b0 = 1;
    s_fpga_p3_b0 = 0;
    s_fpga_p4_b0 = 0;
    repeat (8) begin #5; end
    $display("ROW 0100 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    s_fpga_p0_b0 = 0;
    s_fpga_p2_b0 = 1;
    s_fpga_p3_b0 = 0;
    s_fpga_p4_b0 = 1;
    repeat (8) begin #5; end
    $display("ROW 0101 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    s_fpga_p0_b0 = 0;
    s_fpga_p2_b0 = 1;
    s_fpga_p3_b0 = 1;
    s_fpga_p4_b0 = 0;
    repeat (8) begin #5; end
    $display("ROW 0110 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    s_fpga_p0_b0 = 0;
    s_fpga_p2_b0 = 1;
    s_fpga_p3_b0 = 1;
    s_fpga_p4_b0 = 1;
    repeat (8) begin #5; end
    $display("ROW 0111 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    s_fpga_p0_b0 = 1;
    s_fpga_p2_b0 = 0;
    s_fpga_p3_b0 = 0;
    s_fpga_p4_b0 = 0;
    repeat (8) begin #5; end
    $display("ROW 1000 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    s_fpga_p0_b0 = 1;
    s_fpga_p2_b0 = 0;
    s_fpga_p3_b0 = 0;
    s_fpga_p4_b0 = 1;
    repeat (8) begin #5; end
    $display("ROW 1001 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    s_fpga_p0_b0 = 1;
    s_fpga_p2_b0 = 0;
    s_fpga_p3_b0 = 1;
    s_fpga_p4_b0 = 0;
    repeat (8) begin #5; end
    $display("ROW 1010 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    s_fpga_p0_b0 = 1;
    s_fpga_p2_b0 = 0;
    s_fpga_p3_b0 = 1;
    s_fpga_p4_b0 = 1;
    repeat (8) begin #5; end
    $display("ROW 1011 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    s_fpga_p0_b0 = 1;
    s_fpga_p2_b0 = 1;
    s_fpga_p3_b0 = 0;
    s_fpga_p4_b0 = 0;
    repeat (8) begin #5; end
    $display("ROW 1100 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    s_fpga_p0_b0 = 1;
    s_fpga_p2_b0 = 1;
    s_fpga_p3_b0 = 0;
    s_fpga_p4_b0 = 1;
    repeat (8) begin #5; end
    $display("ROW 1101 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    s_fpga_p0_b0 = 1;
    s_fpga_p2_b0 = 1;
    s_fpga_p3_b0 = 1;
    s_fpga_p4_b0 = 0;
    repeat (8) begin #5; end
    $display("ROW 1110 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    s_fpga_p0_b0 = 1;
    s_fpga_p2_b0 = 1;
    s_fpga_p3_b0 = 1;
    s_fpga_p4_b0 = 1;
    repeat (8) begin #5; end
    $display("ROW 1111 '%b','%b','%b'", s_fpga_p5_b0, s_fpga_p6_b0, s_fpga_p1_b0);
    $finish;
  end
endmodule
