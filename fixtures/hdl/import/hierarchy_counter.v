module and_bus2(input [1:0] a, b, output [1:0] y);
  assign y = a & b;
endmodule

module hierarchy_counter(input clk, reset, input [1:0] a, b,
                         output [1:0] and_y, output reg [1:0] count = 0);
  and_bus2 pair(.a(a), .b(b), .y(and_y));
  always @(posedge clk)
    if (reset) count <= 0;
    else count <= count + 1'b1;
endmodule
