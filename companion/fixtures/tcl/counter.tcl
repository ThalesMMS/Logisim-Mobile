# Portable callback example for the safe companion replay and desktop TclGeneric.
set count 0
proc enableLogisim {enabled} {}
proc logisimExamine {} {}
proc logisimForce {} {
    if {[examine rst_i] eq "1"} {set ::count 0} else {incr ::count}
    set bits ""
    for {set bit 3} {$bit >= 0} {incr bit -1} {
        append bits [expr {($::count >> $bit) & 1}]
    }
    force cpt_o $bits
}
