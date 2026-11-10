library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity Counter_Gen is
  generic (
    WIDTH : positive := 8;
    START, STEP : natural := 3;
    OFFSET : integer := 31;
    MODE : integer
  );
  port (
    clk, rst : in std_logic;
    load_i : in std_logic_vector(7 downto 0);
    q_o : out std_logic_vector(7 downto 0);
    nibble_o : out std_logic_vector(5 downto 2);
    carry_o : out std_logic
  );
end entity Counter_Gen;

architecture rtl of Counter_Gen is
begin
  q_o <= load_i;
  nibble_o <= load_i(3 downto 0);
  carry_o <= clk and rst;
end rtl;
