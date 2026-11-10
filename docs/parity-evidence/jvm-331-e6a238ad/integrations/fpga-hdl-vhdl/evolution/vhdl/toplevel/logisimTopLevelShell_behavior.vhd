--==============================================================================
--== Logisim-evolution goes FPGA automatic generated VHDL code                ==
--== https://github.com/logisim-evolution/                                    ==
--==                                                                          ==
--==                                                                          ==
--== Project   : fpga-comb                                                    ==
--== Component : logisimTopLevelShell                                         ==
--==                                                                          ==
--==============================================================================

ARCHITECTURE platformIndependent OF logisimTopLevelShell IS 

   -----------------------------------------------------------------------------
   -- Here all used components are defined                                    --
   -----------------------------------------------------------------------------
      COMPONENT main
         PORT ( a : IN  std_logic;
                b : IN  std_logic;
                c : IN  std_logic;
                d : IN  std_logic;
                w : OUT std_logic;
                y : OUT std_logic;
                z : OUT std_logic );
      END COMPONENT;

--------------------------------------------------------------------------------
-- All used signals are defined here                                          --
--------------------------------------------------------------------------------
   SIGNAL s_a : std_logic;
   SIGNAL s_b : std_logic;
   SIGNAL s_c : std_logic;
   SIGNAL s_d : std_logic;
   SIGNAL s_w : std_logic;
   SIGNAL s_y : std_logic;
   SIGNAL s_z : std_logic;

BEGIN

   --------------------------------------------------------------------------------
   -- All signal adaptations are performed here                                  --
   --------------------------------------------------------------------------------
   s_a <= a_0;
   s_b <= b_0;
   s_c <= c_0;
   s_d <= d_0;
   w_0 <= s_w;
   y_0 <= s_y;
   z_0 <= s_z;

   --------------------------------------------------------------------------------
   -- The toplevel component is connected here                                   --
   --------------------------------------------------------------------------------
   CIRCUIT_0 : main
      PORT MAP ( a => s_a,
                 b => s_b,
                 c => s_c,
                 d => s_d,
                 w => s_w,
                 y => s_y,
                 z => s_z );
END platformIndependent;
