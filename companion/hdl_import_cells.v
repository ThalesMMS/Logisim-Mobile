// Logic cells accepted by the desktop BLIF component. Sequential cells start at zero.
module BUF(input A, output Y); assign Y=A; endmodule
module BUS(input A,B, output Y); assign Y=A; assign Y=B; endmodule
module NOT(input A, output Y); assign Y=~A; endmodule
module AND(input A,B, output Y); assign Y=A&B; endmodule
module OR(input A,B, output Y); assign Y=A|B; endmodule
module XOR(input A,B, output Y); assign Y=A^B; endmodule
module XNOR(input A,B, output Y); assign Y=~(A^B); endmodule
module NXOR(input A,B, output Y); assign Y=~(A^B); endmodule
module NAND(input A,B, output Y); assign Y=~(A&B); endmodule
module NOR(input A,B, output Y); assign Y=~(A|B); endmodule
module ANDNOT(input A,B, output Y); assign Y=A&~B; endmodule
module ORNOT(input A,B, output Y); assign Y=A|~B; endmodule
module MUX(input A,B,S, output Y); assign Y=S?B:A; endmodule
module TRIS(input A,B, output Y); assign Y=B?A:1'bz; endmodule
module TRISI(input A,B, output Y); assign Y=B?1'bz:A; endmodule
module PULLUP(output Y); assign Y=1'b1; endmodule
module PULLDOWN(output Y); assign Y=1'b0; endmodule
module DFF(input D,C, output reg Q=0); always @(posedge C) Q<=D; endmodule
module DFFSR(input D,C,S,R, output reg Q=0);
 always @(posedge C or posedge S or posedge R) if (R) Q<=0; else if (S) Q<=1; else Q<=D;
endmodule
module DLATCH(input D,E, output reg Q=0); always @* if(E) Q=D; endmodule
