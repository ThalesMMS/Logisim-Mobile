"""The fixed Xilinx iMPACT flash path for the BASYS2 XCF02S PROM."""
import hashlib
import re

from companion.api import JobFailure
from companion.fpga import _execute, _quote


_IDENTIFIED_DEVICE = re.compile(
    r"(?im)^\s*INFO:iMPACT:501\s*-\s*'([0-9]+)':\s*Added Device ([a-z0-9]+) successfully\.\s*$"
)
_IMPACT_ERROR = re.compile(r"(?im)^\s*(?:ERROR:|Program Failed\b)")


def _batch(ctx, stage, commands):
    script = ctx.working_directory / f"{stage}.cmd"
    script.write_text(commands + "quit\n", encoding="utf-8")
    result = _execute(ctx, stage, [ctx.tool("impact"), "-batch", str(script)])
    output = f"{result.stdout}\n{result.stderr}"
    if _IMPACT_ERROR.search(output):
        raise JobFailure("fpga-stage-failed", f"iMPACT reported an error at {stage}.", {"stage": stage})
    return output


def program_basys2_prom(ctx, target, bitstream):
    mcs = ctx.output_path("design.mcs")
    prom_model = target.flash_device.lower()
    _batch(ctx, "generate-prom-mcs",
           "setMode -pff\nsetSubMode -pffserial\n"
           f"addPromDevice -p 1 -size 0 -name {prom_model}\n"
           'addDesign -version 0 -name "0"\naddDeviceChain -index 0\n'
           f"addDevice -p 1 -file {_quote(bitstream)}\n"
           f"generate -format mcs -fillvalue FF -output {_quote(mcs)}\n")
    if not mcs.is_file() or not 0 < mcs.stat().st_size <= ctx.max_output_bytes:
        raise JobFailure("fpga-invalid-mcs", "iMPACT did not produce a usable MCS file.", {"stage": "generate-prom-mcs"})

    fpga_position = target.chain_position + 1
    prom_position = target.flash_chain_position + 1
    scan = f"setMode -bs\nsetCable -port {target.cable}\nidentify\n"
    output = _batch(ctx, "identify-prom-chain", scan)
    devices = sorted((int(position), model.lower()) for position, model in _IDENTIFIED_DEVICE.findall(output))
    if devices != [(fpga_position, "xc3s100e"), (prom_position, prom_model)]:
        raise JobFailure("fpga-prom-chain-mismatch", "The selected cable did not report the approved BASYS2 chain.",
                         {"stage": "identify-prom-chain"})

    mcs_hash = hashlib.sha256(mcs.read_bytes()).hexdigest()
    output = _batch(ctx, "program-prom",
                    scan + f"assignFile -p {prom_position} -file {_quote(mcs)}\nprogram -p {prom_position} -e -v\n")
    if not re.search(rf"(?im)^\s*(?:'{prom_position}':\s*Programmed successfully\.|Program Succeeded\.?)\s*$", output):
        raise JobFailure("fpga-program-unverified", "iMPACT did not report a successful PROM program.",
                         {"stage": "program-prom"})
    return {"flashDevice": target.flash_device, "flashChainPosition": target.flash_chain_position,
            "bitstreamSha256": hashlib.sha256(bitstream.read_bytes()).hexdigest(), "mcsSha256": mcs_hash}
