import subprocess
from multiprocessing import Pool
import os
import re
#import matplotlib.pyplot as plt
import csv

def count_bad_keywords(filename):
    bad_count = 0
    try:
        with open(filename, 'r') as file:
            for line in file:
                parts = line.rstrip().split()
                if parts and parts[1] == 'bad':
                    bad_count += 1
    except FileNotFoundError:
        print(f"File {filename} not found.")
    except Exception as e:
        print(f"An error occurred: {e}")
    return bad_count

def get_filename_without_extension(filepath):
    return os.path.splitext(os.path.basename(filepath))[0]

def execute_command(p, k_value, folder_name, filename):
    vcd_filename = f"./{folder_name}/p_{p}.vcd"
    log_filename = f"./{folder_name}/log_p{p}.txt"
    command = f"time ../oss-cad-suite/bin/pono --bmc-bound-start {k_value} --reset reset --vcd {vcd_filename} --witness --verbosity 1 -k {k_value} -p {p} {filename}"
    return run_subprocess(command, log_filename)

def run_subprocess(command, log_filename):
    with open(log_filename, "w") as log_file:
        process = subprocess.Popen(command, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        stderr_lines = []
        for stdout_line in iter(process.stdout.readline, ''):
            log_file.write(stdout_line)
        for stderr_line in iter(process.stderr.readline, ''):
            log_file.write(stderr_line)
            stderr_lines.append(stderr_line)
        process.stdout.close()
        process.stderr.close()
        process.wait()
        stderr = ''.join(stderr_lines)
        return extract_time_from_log(stderr)

def extract_time_from_log(stderr):
    match = re.search(r'(\d+):(\d+)\.(\d+)', stderr)
    if match:
        minutes, seconds, ms = map(int, match.groups())
        total_seconds = minutes * 60 + seconds + ms * 0.001
        return total_seconds
    return 0

def plot_execution_times(times, k_value, folder_name):
    pass

def write_times_to_csv(times, folder_name):
    csv_filename = f"./{folder_name}/execution_times.csv"
    with open(csv_filename, "w", newline='') as csvfile:
        csv_writer = csv.writer(csvfile)
        csv_writer.writerow(["p", "Execution Time (seconds)"])
        for p, time in enumerate(times):
            csv_writer.writerow([p, time])

def process_file_with_k(filename, k_value):
    file_base_name = get_filename_without_extension(filename)
    folder_name = f"{file_base_name}_log_k={k_value}"
    
    if not os.path.exists(folder_name):
        os.makedirs(folder_name)
    
    bad_count = count_bad_keywords(filename)
    if bad_count == 0:
        print(f"No 'bad' keywords found in {filename}.")
        return

    with Pool(processes=bad_count) as pool:
        times = pool.starmap(execute_command, [(p, k_value, folder_name, filename) for p in range(bad_count)])
    
    write_times_to_csv(times, folder_name)
    #plot_execution_times(times, k_value, folder_name)

def main():
    # filenames = ['./CoreSoc_E2.btor', './CoreSoc_E3.btor', './CoreSoc_E5.btor']
    # filenames = ['./E1.btor', './E2.btor', './E3.btor', './E4.btor', './E5.btor']
    # filenames = ['./E1.btor', './E2.btor', './E3.btor', './E4.btor', './E5.btor']
    filenames = ['./test_run_dir/ZhoushanFormal_should_pass/Core.btor']
    # filenames = ['./NutCore_E1.btor', './NutCore_E2.btor', './NutCore_E5.btor']
    # filenames = ['./NutCore_E4.btor']
    k_values = [22]

    for k_value in k_values:
        for filename in filenames:
            process_file_with_k(filename, k_value)

if __name__ == "__main__":
    main()
