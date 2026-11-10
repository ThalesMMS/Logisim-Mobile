LIBRARY ieee;
USE ieee.std_logic_1164.all;

ENTITY counter IS
  PORT (
    clk : IN std_logic;
    q   : OUT std_logic_vector(3 DOWNTO 0)
  );
END counter;

ARCHITECTURE behavior OF counter IS
BEGIN
  q <= "0000";
END behavior;
