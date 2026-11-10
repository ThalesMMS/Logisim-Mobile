library ieee;
use ieee.std_logic_1164.all;

entity noisy_gate is
  port(a: in std_logic; y: out std_logic);
end entity;

architecture rtl of noisy_gate is
begin
  process(a)
  begin
    if a = '1' then
      for i in 1 to 10000 loop
        report "Fixture diagnostic output limit" severity note;
      end loop;
    end if;
    y <= a;
  end process;
end architecture;
