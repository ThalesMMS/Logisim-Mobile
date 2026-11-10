module gates(input [1:0] a, b, input select,
             output [1:0] and_y, xor_y, mux_y);
  assign and_y = a & b;
  assign xor_y = a ^ b;
  assign mux_y = select ? b : a;
endmodule
