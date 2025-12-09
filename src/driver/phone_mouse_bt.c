#include <linux/delay.h>
#include <linux/init.h>
#include <linux/input.h>
#include <linux/kernel.h>
#include <linux/kthread.h>
#include <linux/module.h>
#include <linux/net.h>
#include <net/bluetooth/bluetooth.h>
#include <net/bluetooth/rfcomm.h>

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Bluetooth-controlled virtual mouse\n"
                   "Works with android phones and an app\n"
                   "Tested on kernel 6.17.8-zen1-1-zen");
MODULE_AUTHOR("Zvyagin Daniil");

static int speed_pct = 100;
module_param(speed_pct, int, 0644);
MODULE_PARM_DESC(speed_pct, "Mouse speed in percent (100 = normal, 50 = half, "
                            "200 = double, negatives invert)");

static int interp_steps = 0;
module_param(interp_steps, int, 0644);
MODULE_PARM_DESC(interp_steps, "Interpolation steps (0=off)");

static struct input_dev *pm_input_dev;

static struct socket *listen_sock = NULL;
static struct socket *client_sock = NULL;

static struct task_struct *rx_thread = NULL;

static int bt_listen_channel = 1;
static int speed_mult = 65536; // 1.0 in Q16.16

#define LMB_MASK 0b00000001
#define RMB_MASK 0b00000010
static void handle_buttons(u8 buttons) {
  if (buttons & LMB_MASK) {
    input_report_key(pm_input_dev, BTN_LEFT, 1);
    input_sync(pm_input_dev);
    input_report_key(pm_input_dev, BTN_LEFT, 0);
    input_sync(pm_input_dev);
  }

  if (buttons & RMB_MASK) {
    input_report_key(pm_input_dev, BTN_RIGHT, 1);
    input_sync(pm_input_dev);
    input_report_key(pm_input_dev, BTN_RIGHT, 0);
    input_sync(pm_input_dev);
  }
}

static void handle_movement(u8 buf[4]) {
  s16 dx = (s16)((buf[0] << 8) | buf[1]);
  s16 dy = (s16)((buf[2] << 8) | buf[3]);

  dx = (dx * speed_mult) >> 16;
  dy = (dy * speed_mult) >> 16;

  if (interp_steps > 0) {
    int step_dx = dx / interp_steps;
    int step_dy = dy / interp_steps;

    int i;
    for (i = 0; i < interp_steps; i++) {
      input_report_rel(pm_input_dev, REL_X, step_dx);
      input_report_rel(pm_input_dev, REL_Y, step_dy);
      input_sync(pm_input_dev);
    }

    return;
  }

  input_report_rel(pm_input_dev, REL_X, dx);
  input_report_rel(pm_input_dev, REL_Y, dy);

  input_sync(pm_input_dev);
}

static int rx_loop(void *data) {
  struct msghdr msg = {0};
  struct kvec vec;
  u8 buf[5];

  while (!kthread_should_stop()) {

    if (!client_sock) {
      // wait for connection
      struct socket *new_sock = NULL;
      int r = kernel_accept(listen_sock, &new_sock, 0);
      if (r == 0) {
        client_sock = new_sock;
        pr_info("phone_mouse: client connected!\n");
      } else {
        ssleep(1);
        continue;
      }
    }

    vec.iov_base = buf;
    vec.iov_len = sizeof(buf);

    int len =
        kernel_recvmsg(client_sock, &msg, &vec, 1, sizeof(buf), MSG_DONTWAIT);

    if (len == -EAGAIN) {
      msleep(5);
      continue;
    }
    if (len <= 0) {
      pr_info("phone_mouse: client disconnected\n");
      sock_release(client_sock);
      client_sock = NULL;
      continue;
    }
    if (len < 5)
      continue;

    handle_buttons(buf[0]);

    handle_movement(&buf[1]);
  }

  return 0;
}

static int __init pm_init(void) {
  int err;
  struct sockaddr_rc addr = {0};

  if (interp_steps < 0) {
    pr_err("phone_mouse_bt: ERROR: interp_steps must be >= 0 (got %d)\n",
           interp_steps);
    return -EINVAL;
  }

  speed_mult = (speed_pct * 65536) / 100;
  pr_info("phone_mouse_bt: speed coefficient = %d (Q16.16)\n", speed_mult);

  // Allocate new input device
  pm_input_dev = input_allocate_device();
  if (!pm_input_dev)
    return -ENOMEM;

  pm_input_dev->name = "Bluetooth Phone Mouse";
  pm_input_dev->id.bustype = BUS_BLUETOOTH;

  __set_bit(EV_KEY, pm_input_dev->evbit);
  __set_bit(EV_REL, pm_input_dev->evbit);

  __set_bit(BTN_LEFT, pm_input_dev->keybit);
  __set_bit(BTN_RIGHT, pm_input_dev->keybit);

  __set_bit(REL_X, pm_input_dev->relbit);
  __set_bit(REL_Y, pm_input_dev->relbit);

  err = input_register_device(pm_input_dev);
  if (err)
    return err;

  // RFCOMM socket
  err = sock_create_kern(&init_net, PF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM,
                         &listen_sock);
  if (err < 0) {
    pr_err("phone_mouse: sock_create_kern failed\n");
    return err;
  }

  addr.rc_family = AF_BLUETOOTH;
  bacpy(&addr.rc_bdaddr, BDADDR_ANY);
  addr.rc_channel = bt_listen_channel;

  err = listen_sock->ops->bind(listen_sock, (struct sockaddr *)&addr,
                               sizeof(addr));
  if (err < 0) {
    pr_err("phone_mouse: bind failed\n");
    return err;
  }

  err = listen_sock->ops->listen(listen_sock, 1);
  if (err < 0) {
    pr_err("phone_mouse: listen failed\n");
    return err;
  }

  // Main loop in kernel thread
  rx_thread = kthread_run(rx_loop, NULL, "phone_mouse_rx");
  if (IS_ERR(rx_thread)) {
    pr_err("phone_mouse: failed to start thread\n");
    return PTR_ERR(rx_thread);
  }

  pr_info("phone_mouse: module loaded, listening RFCOMM channel %d\n",
          bt_listen_channel);

  return 0;
}

static void __exit pm_exit(void) {
  if (rx_thread)
    kthread_stop(rx_thread);

  if (client_sock)
    sock_release(client_sock);

  if (listen_sock)
    sock_release(listen_sock);

  input_unregister_device(pm_input_dev);

  pr_info("phone_mouse: unloaded\n");
}

module_init(pm_init);
module_exit(pm_exit);
