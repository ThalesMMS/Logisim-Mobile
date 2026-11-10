library ieee;
use ieee.std_logic_1164.all;

entity failing_gate is
  port(a: in std_logic; y: out std_logic);
end entity;

architecture rtl of failing_gate is
begin
  assert a /= '1' report "Fixture assertion failure" severity failure;
  y <= a;
end architecture;
