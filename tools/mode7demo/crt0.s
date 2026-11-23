@ Arranque mínimo de una ROM de GBA
	.section .text.start, "ax"
	.arm
	.global _start
_start:
	b	rom_start
	.fill	156, 1, 0	@ logo (el emulador no lo exige)
	.ascii	"MODE7 DEMO\0\0"	@ título (12)
	.ascii	"M7DE"		@ código
	.ascii	"00"		@ fabricante
	.byte	0x96, 0, 0
	.fill	7, 1, 0
	.byte	0		@ versión
	.byte	0		@ checksum (lo corrige fix_header.py)
	.fill	2, 1, 0

rom_start:
	mov	r0, #0x12	@ modo IRQ
	msr	cpsr_c, r0
	ldr	sp, =0x03007FA0
	mov	r0, #0x1F	@ modo sistema
	msr	cpsr_c, r0
	ldr	sp, =0x03007F00

	ldr	r0, =__data_lma
	ldr	r1, =__data_start
	ldr	r2, =__data_end
1:	cmp	r1, r2
	ldrlt	r3, [r0], #4
	strlt	r3, [r1], #4
	blt	1b

	ldr	r1, =__bss_start
	ldr	r2, =__bss_end
	mov	r3, #0
2:	cmp	r1, r2
	strlt	r3, [r1], #4
	blt	2b

	ldr	r0, =main
	bx	r0
	.pool
