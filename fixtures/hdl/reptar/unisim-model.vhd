library ieee;
use ieee.std_logic_1164.all;

package vcomponents is
  component IOBUF is
    generic (DRIVE : integer; IOSTANDARD, SLEW : string);
    port (I, T : in std_logic; O : out std_logic; IO : inout std_logic);
  end component;
end package;

library ieee;
use ieee.std_logic_1164.all;

entity IOBUF is
  generic (DRIVE : integer; IOSTANDARD, SLEW : string);
  port (I, T : in std_logic; O : out std_logic; IO : inout std_logic);
end entity;

architecture test_model of IOBUF is
begin
  assert DRIVE = 12 and IOSTANDARD = "LVCMOS18" and SLEW = "FAST"
    report "Unexpected Reptar IOBUF parameters" severity failure;
  IO <= I when T = '0' else 'Z';
  O <= IO;
end architecture;
