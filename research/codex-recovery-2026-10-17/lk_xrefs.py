from pathlib import Path
import capstone,struct
from capstone.arm import *
b=Path('BIGME_B1051C_system_dump/lk_b.bin').read_bytes()[512:];base=0x48200000
md=capstone.Cs(capstone.CS_ARCH_ARM,capstone.CS_MODE_THUMB);md.detail=True;md.skipdata=True
ins=list(md.disasm(b[0x138:0x78000],base+0x138));known={}
for j,i in enumerate(ins):
 if i.id==0:known={};continue
 op=i.operands
 try:
  if i.id==ARM_INS_LDR and op[1].type==ARM_OP_MEM and op[1].mem.base==ARM_REG_PC:
   addr=((i.address+4)&~3)+op[1].mem.disp;val=struct.unpack_from('<I',b,addr-base)[0];known[op[0].reg]=(val,i.address)
  elif i.id==ARM_INS_ADD and any(o.type==ARM_OP_REG and o.reg==ARM_REG_PC for o in op[1:]) and op[0].reg in known:
   val,origin=known.pop(op[0].reg);target=(val+i.address+4)&0xffffffff;off=target-base
   if 0<=off<len(b) and any(abs(off-t)<4 for t in [0xb7b48,0xb7b6c,0xb7ae4,0x9f954,0x9aa04]):
    print('XREF',hex(i.address),'target',hex(target),b[off:off+80].split(b'\0')[0])
    for z in ins[max(0,j-15):j+30]:print(hex(z.address),z.mnemonic,z.op_str)
  elif i.id in [ARM_INS_B,ARM_INS_POP]:known={}
 except (IndexError,struct.error):pass
