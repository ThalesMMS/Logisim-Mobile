module leaf(input a, b, output y);
  assign y = a ^ b;
endmodule

module hierarchy(input a, b, output y);
  leaf nested(.a(a), .b(b), .y(y));
endmodule
