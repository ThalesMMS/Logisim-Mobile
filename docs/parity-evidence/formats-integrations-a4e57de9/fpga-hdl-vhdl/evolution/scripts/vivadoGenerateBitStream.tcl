open_project -verbose /var/folders/cn/22v8t4rn735d20w21scl19m40000gn/T/tmpfgzmdtcv/home-vhdl/logisim_evolution_workspace/fpga-comb/main/sandbox//vp/vp.xpr
update_compile_order -fileset sources_1
launch_runs synth_1
wait_on_run synth_1
launch_runs impl_1 -to_step write_bitstream -jobs 8
wait_on_run impl_1
exit
