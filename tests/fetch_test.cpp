#include "VFrontend.h"
#include "verilated.h"
#include <cstdint>
#include <deque>
#include <iostream>
#include <map>
#include <optional>
#include <stdexcept>

static void check(bool ok, const char* message) {
    if (!ok) throw std::runtime_error(message);
}
struct Packet { uint32_t pc, inst, next, history; };
struct Request { uint32_t addr; uint64_t due; };
struct Sim {
    VFrontend dut;
    uint64_t cycle = 0, reads = 0;
    bool holdResponses = false;
    std::deque<Request> pending;
    std::map<uint32_t, uint32_t> program;
    Sim() {
        dut.clock = 0; dut.reset = 1;
        dut.io_out_ready = 0;
        dut.io_mem_req_ready = 0;
        dut.io_mem_rsp_valid = 0;
        dut.io_mem_rsp_bits_data = 0; dut.io_mem_rsp_bits_error = 0;
        dut.io_redirect_valid = 0; dut.io_redirect_bits = 0;
        dut.io_train_valid = 0; dut.io_commit_valid = 0;
        dut.io_train_bits_pc = 0; dut.io_train_bits_target = 0;
        dut.io_train_bits_taken = 0; dut.io_train_bits_conditional = 0;
        dut.io_train_bits_call = 0; dut.io_train_bits_ret = 0; dut.io_train_bits_history = 0;
        dut.io_commit_bits_pc = 0; dut.io_commit_bits_target = 0;
        dut.io_commit_bits_taken = 0; dut.io_commit_bits_conditional = 0;
        dut.io_commit_bits_call = 0; dut.io_commit_bits_ret = 0; dut.io_commit_bits_history = 0;
        for (int i = 0; i < 5; ++i) tick(false);
        dut.reset = 0;
    }
    uint32_t word(uint32_t addr) const {
        auto it = program.find(addr);
        return it == program.end() ? 0x13 : it->second; // ADDI x0,x0,0
    }
    std::optional<Packet> tick(bool ready = true) {
        dut.clock = 0;
        dut.io_out_ready = ready;
        dut.io_mem_req_ready = !dut.reset && cycle % 5 != 0;
        dut.io_mem_rsp_valid = !dut.reset && !holdResponses && !pending.empty()
            && pending.front().due <= cycle;
        dut.io_mem_rsp_bits_data = pending.empty() ? 0 : word(pending.front().addr);
        dut.eval();
        if (dut.io_redirect_valid) check(!dut.io_out_valid, "redirect exposed a killed instruction");
        std::optional<Packet> out;
        if (dut.io_out_valid && ready && !dut.reset && !dut.io_redirect_valid)
            out = Packet{dut.io_out_bits_pc, dut.io_out_bits_inst,
                         dut.io_out_bits_next, dut.io_out_bits_history};
        bool response = dut.io_mem_rsp_valid && dut.io_mem_rsp_ready;
        bool request = dut.io_mem_req_valid && dut.io_mem_req_ready;
        uint32_t addr = dut.io_mem_req_bits_addr;
        if (request) {
            check(!dut.io_mem_req_bits_write, "fetch issued a write");
            check((addr & 3) == 0, "unaligned fetch address");
        }
        dut.clock = 1; dut.eval();
        if (response) pending.pop_front();
        if (request) { pending.push_back({addr, cycle + 2 + cycle % 11}); ++reads; }
        ++cycle;
        return out;
    }
    Packet next() {
        for (int i = 0; i < 5000; ++i) if (auto p = tick()) return *p;
        throw std::runtime_error("fetch timed out");
    }
    void redirect(uint32_t pc) {
        dut.io_redirect_valid = 1; dut.io_redirect_bits = pc;
        tick(false); dut.io_redirect_valid = 0;
    }
    void expect(uint32_t pc, uint32_t nextPC) {
        auto p = next();
        if (p.pc != pc || p.inst != word(pc) || p.next != nextPC) {
            std::cerr << "expected pc=" << std::hex << pc << " next=" << nextPC
                      << " got pc=" << p.pc << " next=" << p.next << '\n';
            throw std::runtime_error("incorrect fetch packet");
        }
    }
};
int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    try {
        {
            Sim s;
            for (uint32_t pc = 0; pc < 512; pc += 4) {
                // Let the queue fill at multiple points, including cache misses.
                if (pc % 48 == 0) for (int i = 0; i < 40; ++i) s.tick(false);
                s.expect(pc, pc + 4);
            }
            s.redirect(0);
            auto before = s.reads;
            for (uint32_t pc = 0; pc < 128; pc += 4) s.expect(pc, pc + 4);
            check(s.reads == before, "warm instruction cache unexpectedly missed");
        }
        {
            Sim s;
            // Hold a real miss through 16 redirects: the old 4-bit epoch would wrap.
            s.holdResponses = true;
            for (int i = 0; i < 30; ++i) s.tick(false);
            check(!s.pending.empty(), "test failed to create an outstanding miss");
            for (int i = 0; i < 16; ++i) s.redirect(0x400 + i * 64);
            s.holdResponses = false;
            s.expect(0x7c0, 0x7c4);
            // Flush an occupied output queue as well as outstanding metadata.
            for (int i = 0; i < 100; ++i) s.tick(false);
            s.redirect(0x1000); s.expect(0x1000, 0x1004);
        }
        {
            Sim s;
            s.program[0] = 0x010000ef; // JAL x1,+16 (cold call)
            s.program[16] = 0x00008067; // JALR x0,x1,0 (return)
            s.program[4] = 0xffdff06f; // JAL x0,-4
            s.expect(0, 16); s.expect(16, 4); s.expect(4, 0);
            s.expect(0, 16); s.expect(16, 4);
        }
        {
            Sim s;
            s.program[0] = 0x00800463; // BEQ x0,x8,+8, direction initially taken
            s.expect(0, 8); s.expect(8, 12);
            // Train all local-history states to strongly not taken.
            s.dut.io_train_valid = 1; s.dut.io_train_bits_conditional = 1;
            s.dut.io_train_bits_pc = 0; s.dut.io_train_bits_target = 8;
            s.dut.io_train_bits_taken = 0;
            for (int h = 0; h < 4; ++h) {
                s.dut.io_train_bits_history = h;
                for (int i = 0; i < 4; ++i) s.tick(false);
            }
            s.dut.io_train_valid = 0;
            for (int i = 0; i < 3; ++i) s.tick(false);
            s.redirect(0); s.expect(0, 4); s.expect(4, 8);
        }
        {
            Sim s;
            s.program[0x200] = 0x00008067;
            // A call retiring on the redirect edge must be in the recovered RAS.
            s.dut.io_commit_valid = 1; s.dut.io_commit_bits_call = 1;
            s.dut.io_commit_bits_pc = 0x100;
            s.redirect(0x200);
            s.dut.io_commit_valid = 0;
            s.expect(0x200, 0x104); s.expect(0x104, 0x108);
        }
        std::cout << "PASS: sequential fetch, backpressure, cache hits, repeated redirects, "
                     "JAL/return repair, predictor training, committed RAS recovery\n";
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n'; return 1;
    }
}
