// Play feature modules must declare the same ABIs as the base app. These tiny
// placeholders retain 32-bit app support; GPU delivery is disabled there.
extern "C" __attribute__((visibility("default"))) int ah_gpu_runtime_available() { return 0; }
