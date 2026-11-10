`include "constants.vh"
module macros(input [`WIDTH-1:0] a, output [`WIDTH-1:0] y);
  assign y = a ^ `MASK;
endmodule
