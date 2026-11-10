-- Generated deterministically from circuit main.
-- VHDL-2008. Defined binary inputs; simulator delays are not represented.
LIBRARY ieee;
USE ieee.std_logic_1164.ALL;
USE ieee.numeric_std.ALL;

ENTITY c_main IS
  PORT (
    p_a : IN std_logic_vector(0 DOWNTO 0);
    p_w : OUT std_logic_vector(0 DOWNTO 0);
    p_b : IN std_logic_vector(0 DOWNTO 0);
    p_c : IN std_logic_vector(0 DOWNTO 0);
    p_d : IN std_logic_vector(0 DOWNTO 0);
    p_y : OUT std_logic_vector(0 DOWNTO 0);
    p_z : OUT std_logic_vector(0 DOWNTO 0)
  );
END ENTITY;

ARCHITECTURE rtl OF c_main IS
  FUNCTION hdl_choose(condition : boolean; yes_value, no_value : std_logic_vector) RETURN std_logic_vector IS
  BEGIN
    IF condition THEN RETURN yes_value; ELSE RETURN no_value; END IF;
  END FUNCTION;
  SIGNAL s_0000 : std_logic;
  SIGNAL s_0001 : std_logic;
  SIGNAL s_0002 : std_logic;
  SIGNAL s_0003 : std_logic;
  SIGNAL s_0004 : std_logic;
  SIGNAL s_0005 : std_logic;
  SIGNAL s_0006 : std_logic;
  SIGNAL v_import_5_out0 : std_logic_vector(0 DOWNTO 0);
  SIGNAL v_import_6_out0 : std_logic_vector(0 DOWNTO 0);
  SIGNAL v_import_7_out0 : std_logic_vector(0 DOWNTO 0);
BEGIN
  s_0000 <= p_a(0);
  p_w(0) <= s_0006;
  s_0001 <= p_b(0);
  s_0002 <= p_c(0);
  s_0003 <= p_d(0);
  p_y(0) <= s_0004;
  p_z(0) <= s_0005;
  s_0004 <= v_import_5_out0(0);
  v_import_5_out0 <= (std_logic_vector'(0 => s_0000)) AND (std_logic_vector'(0 => s_0001));
  s_0005 <= v_import_6_out0(0);
  v_import_6_out0 <= (std_logic_vector'(0 => s_0002)) XOR (std_logic_vector'(0 => s_0003));
  s_0006 <= v_import_7_out0(0);
  v_import_7_out0 <= NOT ((std_logic_vector'(0 => s_0000)) OR (std_logic_vector'(0 => s_0003)));
END ARCHITECTURE;
