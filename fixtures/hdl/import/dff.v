module dff(input clk, reset, d, output reg q = 0);
  always @(posedge clk)
    if (reset) q <= 0;
    else q <= d;
endmodule
