library ieee;
use ieee.std_logic_1164.all;

entity busy_loop is
  port(a: in std_logic; y: out std_logic);
end entity;

architecture rtl of busy_loop is
begin
  process(a)
  begin
    if a = '1' then
      loop
        null;
      end loop;
    end if;
    y <= a;
  end process;
end architecture;
