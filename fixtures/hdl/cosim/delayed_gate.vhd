library ieee;
use ieee.std_logic_1164.all;

entity delayed_gate is
  port(a: in std_logic; y: out std_logic := '0');
end entity;

architecture rtl of delayed_gate is
begin
  y <= a after 3 ns;
end architecture;
