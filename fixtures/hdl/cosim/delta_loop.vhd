library ieee;
use ieee.std_logic_1164.all;

entity delta_loop is
  port(a: in std_logic; y: out std_logic);
end entity;

architecture rtl of delta_loop is
  signal oscillator: std_logic := '0';
begin
  oscillator <= not oscillator;
  y <= oscillator and a;
end architecture;
