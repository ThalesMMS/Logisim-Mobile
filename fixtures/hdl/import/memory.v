module memory(input clk, write_enable, input [1:0] address,
              input [3:0] data, output [3:0] q);
  reg [3:0] words [0:3];
  initial $readmemh("words.hex", words);
  always @(posedge clk)
    if (write_enable) words[address] <= data;
  assign q = words[address];
endmodule
