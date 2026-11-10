library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity counter is
  generic(WIDTH: positive := 4; INITIAL: natural := 0);
  port(clk, reset: in std_logic;
       q: out std_logic_vector(WIDTH - 1 downto 0));
end entity;

architecture rtl of counter is
  signal value: unsigned(WIDTH - 1 downto 0) := to_unsigned(INITIAL, WIDTH);
begin
  process(clk, reset)
  begin
    if reset = '1' then
      value <= to_unsigned(INITIAL, WIDTH);
    elsif rising_edge(clk) then
      value <= value + 1;
    end if;
  end process;
  q <= std_logic_vector(value);
end architecture;
