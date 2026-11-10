module tb;
  reg s_a_0 = 0;
  reg s_b_0 = 0;
  reg s_c_0 = 0;
  reg s_d_0 = 0;
  wire s_w_0;
  wire s_y_0;
  wire s_z_0;
  logisimTopLevelShell dut(.a_0(s_a_0), .b_0(s_b_0), .c_0(s_c_0), .d_0(s_d_0), .w_0(s_w_0), .y_0(s_y_0), .z_0(s_z_0));
  initial begin
    s_a_0 = 0;
    s_b_0 = 0;
    s_c_0 = 0;
    s_d_0 = 0;
    repeat (8) begin #5; end
    $display("ROW 0000 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    s_a_0 = 0;
    s_b_0 = 0;
    s_c_0 = 0;
    s_d_0 = 1;
    repeat (8) begin #5; end
    $display("ROW 0001 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    s_a_0 = 0;
    s_b_0 = 0;
    s_c_0 = 1;
    s_d_0 = 0;
    repeat (8) begin #5; end
    $display("ROW 0010 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    s_a_0 = 0;
    s_b_0 = 0;
    s_c_0 = 1;
    s_d_0 = 1;
    repeat (8) begin #5; end
    $display("ROW 0011 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    s_a_0 = 0;
    s_b_0 = 1;
    s_c_0 = 0;
    s_d_0 = 0;
    repeat (8) begin #5; end
    $display("ROW 0100 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    s_a_0 = 0;
    s_b_0 = 1;
    s_c_0 = 0;
    s_d_0 = 1;
    repeat (8) begin #5; end
    $display("ROW 0101 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    s_a_0 = 0;
    s_b_0 = 1;
    s_c_0 = 1;
    s_d_0 = 0;
    repeat (8) begin #5; end
    $display("ROW 0110 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    s_a_0 = 0;
    s_b_0 = 1;
    s_c_0 = 1;
    s_d_0 = 1;
    repeat (8) begin #5; end
    $display("ROW 0111 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    s_a_0 = 1;
    s_b_0 = 0;
    s_c_0 = 0;
    s_d_0 = 0;
    repeat (8) begin #5; end
    $display("ROW 1000 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    s_a_0 = 1;
    s_b_0 = 0;
    s_c_0 = 0;
    s_d_0 = 1;
    repeat (8) begin #5; end
    $display("ROW 1001 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    s_a_0 = 1;
    s_b_0 = 0;
    s_c_0 = 1;
    s_d_0 = 0;
    repeat (8) begin #5; end
    $display("ROW 1010 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    s_a_0 = 1;
    s_b_0 = 0;
    s_c_0 = 1;
    s_d_0 = 1;
    repeat (8) begin #5; end
    $display("ROW 1011 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    s_a_0 = 1;
    s_b_0 = 1;
    s_c_0 = 0;
    s_d_0 = 0;
    repeat (8) begin #5; end
    $display("ROW 1100 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    s_a_0 = 1;
    s_b_0 = 1;
    s_c_0 = 0;
    s_d_0 = 1;
    repeat (8) begin #5; end
    $display("ROW 1101 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    s_a_0 = 1;
    s_b_0 = 1;
    s_c_0 = 1;
    s_d_0 = 0;
    repeat (8) begin #5; end
    $display("ROW 1110 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    s_a_0 = 1;
    s_b_0 = 1;
    s_c_0 = 1;
    s_d_0 = 1;
    repeat (8) begin #5; end
    $display("ROW 1111 '%b','%b','%b'", s_y_0, s_z_0, s_w_0);
    $finish;
  end
endmodule
