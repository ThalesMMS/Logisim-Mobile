/******************************************************************************
 ** Logisim-evolution goes FPGA automatic generated Verilog code             **
 ** https://github.com/logisim-evolution/                                    **
 **                                                                          **
 ** Component : main                                                         **
 **                                                                          **
 *****************************************************************************/

module main( a,
             b,
             c,
             d,
             w,
             y,
             z );

   /*******************************************************************************
   ** The inputs are defined here                                                **
   *******************************************************************************/
   input a;
   input b;
   input c;
   input d;

   /*******************************************************************************
   ** The outputs are defined here                                               **
   *******************************************************************************/
   output w;
   output y;
   output z;

   /*******************************************************************************
   ** The wires are defined here                                                 **
   *******************************************************************************/
   wire s_logisimNet0;
   wire s_logisimNet1;
   wire s_logisimNet2;
   wire s_logisimNet3;
   wire s_logisimNet4;
   wire s_logisimNet5;
   wire s_logisimNet6;

   /*******************************************************************************
   ** The module functionality is described here                                 **
   *******************************************************************************/

   /*******************************************************************************
   ** Here all input connections are defined                                     **
   *******************************************************************************/
   assign s_logisimNet0 = d;
   assign s_logisimNet1 = c;
   assign s_logisimNet2 = a;
   assign s_logisimNet6 = b;

   /*******************************************************************************
   ** Here all output connections are defined                                    **
   *******************************************************************************/
   assign w = s_logisimNet5;
   assign y = s_logisimNet3;
   assign z = s_logisimNet4;

   /*******************************************************************************
   ** Here all normal components are defined                                     **
   *******************************************************************************/
   AND_GATE #(.BubblesMask(2'b00))
      GATES_1 (.input1(s_logisimNet2),
               .input2(s_logisimNet6),
               .result(s_logisimNet3));

   XOR_GATE_ONEHOT #(.BubblesMask(2'b00))
      GATES_2 (.input1(s_logisimNet1),
               .input2(s_logisimNet0),
               .result(s_logisimNet4));

   NOR_GATE #(.BubblesMask(2'b00))
      GATES_3 (.input1(s_logisimNet2),
               .input2(s_logisimNet0),
               .result(s_logisimNet5));


endmodule
