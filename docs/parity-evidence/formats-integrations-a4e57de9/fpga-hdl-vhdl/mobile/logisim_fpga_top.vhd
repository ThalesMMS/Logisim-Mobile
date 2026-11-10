LIBRARY ieee;
USE ieee.std_logic_1164.ALL;
ENTITY logisim_fpga_top IS
  PORT (
    fpga_p0_b0: IN std_logic;
    fpga_p1_b0: OUT std_logic;
    fpga_p2_b0: IN std_logic;
    fpga_p3_b0: IN std_logic;
    fpga_p4_b0: IN std_logic;
    fpga_p5_b0: OUT std_logic;
    fpga_p6_b0: OUT std_logic
  );
END ENTITY;
ARCHITECTURE rtl OF logisim_fpga_top IS
  SIGNAL design_p0: std_logic_vector(0 DOWNTO 0);
  SIGNAL design_p1: std_logic_vector(0 DOWNTO 0);
  SIGNAL design_p2: std_logic_vector(0 DOWNTO 0);
  SIGNAL design_p3: std_logic_vector(0 DOWNTO 0);
  SIGNAL design_p4: std_logic_vector(0 DOWNTO 0);
  SIGNAL design_p5: std_logic_vector(0 DOWNTO 0);
  SIGNAL design_p6: std_logic_vector(0 DOWNTO 0);
BEGIN
  design_p0(0) <= fpga_p0_b0;
  fpga_p1_b0 <= design_p1(0);
  design_p2(0) <= fpga_p2_b0;
  design_p3(0) <= fpga_p3_b0;
  design_p4(0) <= fpga_p4_b0;
  fpga_p5_b0 <= design_p5(0);
  fpga_p6_b0 <= design_p6(0);
  design_instance: ENTITY work.c_main PORT MAP (
    p_a => design_p0,
    p_w => design_p1,
    p_b => design_p2,
    p_c => design_p3,
    p_d => design_p4,
    p_y => design_p5,
    p_z => design_p6
  );
END ARCHITECTURE;
