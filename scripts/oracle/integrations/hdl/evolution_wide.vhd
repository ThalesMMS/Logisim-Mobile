LIBRARY ieee;
USE ieee.std_logic_1164.ALL;

ENTITY wide_bus IS
  PORT ( a_i : IN std_logic_vector(31 DOWNTO 0);
         b_i : IN std_logic_vector(63 DOWNTO 0);
         sel : IN std_logic;
         y_o : OUT std_logic_vector(63 DOWNTO 0) );
END wide_bus;

ARCHITECTURE rtl OF wide_bus IS
BEGIN
  y_o <= b_i WHEN sel = '1' ELSE (63 DOWNTO 32 => '0') & a_i;
END rtl;
