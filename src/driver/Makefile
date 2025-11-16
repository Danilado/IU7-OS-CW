# Имя модуля
obj-m += phone_mouse_bt.o

# Путь к заголовкам ядра
KDIR := /lib/modules/$(shell uname -r)/build

# Текущая рабочая директория
PWD  := $(shell pwd)

all:
	$(MAKE) -C $(KDIR) M=$(PWD) modules

clean:
	$(MAKE) -C $(KDIR) M=$(PWD) clean
