library ieee;
use ieee.std_logic_1164.all;

entity logic_states is
  port(a: in std_logic_vector(8 downto 0);
       y: out std_logic_vector(8 downto 0));
end entity;

architecture rtl of logic_states is
begin
  y <= a;
end architecture;
