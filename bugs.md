### 这里记录一些zhoushan中遇到的bug

###### data store size

```scala
    st_req.bits.size  := Mux(mmio, uop.mem_size, s"b$MEM_DWORD".U)
```

这里对于非mmio的size均使用64位访存，这里不算bug,但是算mismatch.