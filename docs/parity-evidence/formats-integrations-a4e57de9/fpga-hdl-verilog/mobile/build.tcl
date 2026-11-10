read_verilog {c_main.v}
read_verilog {logisim_fpga_top.v}
read_xdc {pins.xdc}
synth_design -top logisim_fpga_top -part xc7a35tcpg236-1
opt_design
place_design
route_design
report_timing_summary -file timing.txt
write_bitstream -force design.bit
