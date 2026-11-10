`timescale 1ns/1ps
module truth_table;
  reg a;
  reg b;
  wire physical_y;
  wire named_y;
  physical_and physical(.pin_21(a), .pin_22(b), .pin_23(physical_y));
  bitstream_and named(.fpga_p0_b0(a), .fpga_p1_b0(b), .fpga_p2_b0(named_y));
  task check;
    input next_a;
    input next_b;
    input expected;
    begin
      a = next_a;
      b = next_b;
      #1;
      if (physical_y !== expected || named_y !== expected)
        $fatal(1, "a=%b b=%b expected=%b physical=%b named=%b", a,b,expected,physical_y,named_y);
      $display("%b %b -> %b (physical pin 23 and named output)",a,b,physical_y);
    end
  endtask
  initial begin
    check(0,0,0);
    check(0,1,0);
    check(1,0,0);
    check(1,1,1);
    $display("PASS: all four input combinations from unpacked bitstream");
    $finish;
  end
endmodule
