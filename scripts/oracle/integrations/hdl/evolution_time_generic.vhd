library ieee;
use ieee.std_logic_1164.all;

entity delayed is
  generic ( DELAY : time := 5 ns );
  port ( a : in std_logic; y : out std_logic );
end delayed;

architecture rtl of delayed is
begin
  y <= a after DELAY;
end rtl;
