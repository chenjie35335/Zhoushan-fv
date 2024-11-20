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