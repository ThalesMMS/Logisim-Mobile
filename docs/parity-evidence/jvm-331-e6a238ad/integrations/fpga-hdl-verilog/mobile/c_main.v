// Generated deterministically from circuit main.
// Defined binary inputs; simulator propagation delays are not represented.
`default_nettype none
module c_main(p_a, p_w, p_b, p_c, p_d, p_y, p_z);
  input wire [0:0] p_a;
  output wire [0:0] p_w;
  input wire [0:0] p_b;
  input wire [0:0] p_c;
  input wire [0:0] p_d;
  output wire [0:0] p_y;
  output wire [0:0] p_z;
  wire s_0000;
  wire s_0001;
  wire s_0002;
  wire s_0003;
  wire s_0004;
  wire s_0005;
  wire s_0006;
  wire [0:0] v_import_5_out0;
  wire [0:0] v_import_6_out0;
  wire [0:0] v_import_7_out0;
  assign s_0000 = p_a[0];
  assign p_w[0] = s_0006;
  assign s_0001 = p_b[0];
  assign s_0002 = p_c[0];
  assign s_0003 = p_d[0];
  assign p_y[0] = s_0004;
  assign p_z[0] = s_0005;
  assign s_0004 = v_import_5_out0[0];
  assign v_import_5_out0 = ({s_0000}) & ({s_0001});
  assign s_0005 = v_import_6_out0[0];
  assign v_import_6_out0 = ({s_0002}) ^ ({s_0003});
  assign s_0006 = v_import_7_out0[0];
  assign v_import_7_out0 = ~(({s_0000}) | ({s_0003}));
endmodule
`default_nettype wire
