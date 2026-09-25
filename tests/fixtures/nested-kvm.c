// Execute a tiny L2 guest as the unprivileged agent inside a Firecracker guest.
// Presence of /dev/kvm alone does not prove nested virtualization works.
#include <errno.h>
#include <fcntl.h>
#include <linux/kvm.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

static void require(int ok, const char *operation) {
    if (!ok) {
        fprintf(stderr, "%s: %s\n", operation, strerror(errno));
        exit(1);
    }
}

int main(void) {
    require(getuid() == 1000, "probe must run as the agent");
    alarm(10);
    int kvm = open("/dev/kvm", O_RDWR | O_CLOEXEC);
    require(kvm >= 0, "open guest /dev/kvm");
    require(ioctl(kvm, KVM_GET_API_VERSION, 0) == 12, "KVM API version");
    int vm = ioctl(kvm, KVM_CREATE_VM, 0);
    require(vm >= 0, "create nested VM");
    require(ioctl(vm, KVM_SET_TSS_ADDR, 0xfffbd000) == 0, "set TSS address");
    unsigned char *memory = mmap(NULL, 4096, PROT_READ | PROT_WRITE,
                                MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    require(memory != MAP_FAILED, "allocate guest memory");
    // 16-bit real mode: mov ax, 42; hlt.
    memcpy(memory, "\xb8\x2a\x00\xf4", 4);
    struct kvm_userspace_memory_region region = {
        .slot = 0, .guest_phys_addr = 0x1000, .memory_size = 4096,
        .userspace_addr = (unsigned long)memory,
    };
    require(ioctl(vm, KVM_SET_USER_MEMORY_REGION, &region) == 0, "map guest memory");
    int cpu = ioctl(vm, KVM_CREATE_VCPU, 0);
    require(cpu >= 0, "create nested vCPU");
    int size = ioctl(kvm, KVM_GET_VCPU_MMAP_SIZE, 0);
    require(size >= (int)sizeof(struct kvm_run), "vCPU mmap size");
    struct kvm_run *run = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, cpu, 0);
    require(run != MAP_FAILED, "map vCPU");
    struct kvm_sregs special;
    require(ioctl(cpu, KVM_GET_SREGS, &special) == 0, "read segment registers");
    special.cs.base = 0;
    special.cs.selector = 0;
    require(ioctl(cpu, KVM_SET_SREGS, &special) == 0, "set segment registers");
    struct kvm_regs registers = {.rip = 0x1000, .rflags = 2};
    require(ioctl(cpu, KVM_SET_REGS, &registers) == 0, "set registers");
    int result;
    do {
        result = ioctl(cpu, KVM_RUN, 0);
    } while (result < 0 && errno == EINTR);
    require(result == 0, "execute nested guest");
    if (run->exit_reason != KVM_EXIT_HLT) {
        fprintf(stderr, "Unexpected nested guest exit: %u\n", run->exit_reason);
        return 1;
    }
    require(ioctl(cpu, KVM_GET_REGS, &registers) == 0, "read result");
    require(registers.rax == 42, "nested guest must compute 42");
    puts("nested-kvm: agent uid=1000, KVM_RUN, rax=42, HLT");
    munmap(run, size);
    munmap(memory, 4096);
    close(cpu);
    close(vm);
    close(kvm);
    return 0;
}
