### 这里记录一些zhoushan中遇到的bug

###### data store size

```scala
    st_req.bits.size  := Mux(mmio, uop.mem_size, s"b$MEM_DWORD".U)
```

这里对于非mmio的size均使用64位访存，这里不算bug,但是算mismatch.

这里参考模型需要修改，主要要修改这几个部分：
1、 state里面需要添加rs1, rs2, rd,rs1data,rs2data和rddata以及写使能
2、 pc需要自己指定，然后zhoushan中的npc需要传入进行比较，进行assert
3、 在参考模型读取寄存器之前，需要比较rs1, rs2的值是否相等
4、 然后assert语句这样完成：
（1） next里面更新rs1 rs2 rd和rdata以及pc
（2） 返回到checker,添加rs1 rs2和rd以及pc和nextpc的比较
（3） 还是应该将rs1Data和rs2Data写到regs然后再读感觉不需要更新太多代码，比较简单

以上的所有都是保证其在一个周期内可以出结果，使得其验证可以说是瞬间出来的

This is a bug found in zhoushan. The judgement of unaligned reading from and writing from memory is not fully covered.

我个人感觉还是说给读的地址和其他的比较复杂，所以还是说将所有当前的csr值传到后面去
这样的情况下需要传给参考模型的只有下面这些信号：
1、 写CSR寄存器地址，写CSR寄存器的值
2、 写通用寄存器地址，写通用寄存器值
我的想法还是提取寄存器的更新值，比较更新值

参考模型是这样，就是说如果rs1是0,则不考虑读写，这部分还是需要考虑和商榷的

首先这里进行一些riscv-spec的解读：
（1） 首先csrrw指令需要必须保证说csr寄存器的地址为可读可写寄存器，如果不是会发生异常，csrrs和csrrc不一定，他是允许某些位是不可写的，这些位不进行写操作就行
（2） 关于是否为0,这个比较简单，按照相关的进行操作就可以了
（3） 我感觉我的那个操作还是存在问题：首先如果dut的wr为false,不需要比较参考模型的wr，而是应该比较旧的值是否和新的值相同。表面上看可能会有corner
cases,但是只要空间足够大，还是有可能发现的。
（4） 个人感觉应该这么干

还是决定说先不产生异常，就单独验证读写的功能
