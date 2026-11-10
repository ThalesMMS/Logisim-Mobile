read_vhdl {c_main.vhd}
read_vhdl {logisim_fpga_top.vhd}
read_xdc {pins.xdc}
synth_design -top logisim_fpga_top -part xc7a35tcpg236-1
opt_design
place_design
route_design
report_timing_summary -file timing.txt
write_bitstream -force design.bit
