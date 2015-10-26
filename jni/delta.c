/*
 * Copyright (C) 2013 Jorrit "Chainfire" Jongma
 * Copyright (C) 2013 The OmniROM Project
 */
/*
 * This file is part of OpenDelta.
 *
 * OpenDelta is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenDelta is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenDelta. If not, see <http://www.gnu.org/licenses/>.
 */

#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include "xdelta3-3.0.7/xdelta3.h"
#include "delta.h"

static int xerror(char* message) {
	fprintf(stderr, "%s\n", message);
	return 0;
}

int dedelta(char* filenameSource, char* filenameDelta, char* filenameOut) {
	int ok = 0;

	int fsource = open(filenameSource, O_RDONLY);
	int fdelta = open(filenameDelta, O_RDONLY);
	unlink(filenameOut);
	int fout = open(filenameOut, O_CREAT | O_WRONLY, 0644);

	if ((fsource >= 0) && (fdelta >= 0) && (fout >= 0)) {
		int CHUNK = 256 * 1024;
		unsigned char* bsource = (unsigned char*)malloc(CHUNK);
		if (bsource == NULL) return xerror("Malloc failed");
		unsigned char* bdelta = (unsigned char*)malloc(CHUNK);
		if (bdelta == NULL) return xerror("Malloc failed");

		int ret;
		xd3_stream stream;
		xd3_config config;

		xd3_init_config (&config, 0 /* flags */);
		config.winsize = 32768;
		ret = xd3_config_stream (&stream, &config);

		if (ret != 0) return xerror("Error #1");

		xd3_source source;

		source.name = filenameSource;
		source.ioh = NULL;
		source.blksize = CHUNK;
		source.curblkno = (xoff_t) -1;
		source.curblk = bsource;
		source.max_winsize = CHUNK;

		ret = xd3_set_source (&stream, &source);

		if (ret != 0) return xerror("Error #2");

		int eof = 0;
		do {
			size_t r = read(fdelta, bdelta, CHUNK);
			if (r <= 0) {
				eof = 1;
				xd3_set_flags (&stream, XD3_FLUSH);
			}
			xd3_avail_input (&stream, bdelta, r);
		process:
			ret = xd3_decode_input (&stream);
			switch (ret) {
			case XD3_INPUT:
				continue;
			case XD3_OUTPUT:
				/* write data */
				if (stream.avail_out > 0) if (write(fout, stream.next_out, stream.avail_out) != stream.avail_out) return xerror("Write error");
				xd3_consume_output(&stream);
				goto process;
			case XD3_GETSRCBLK:
				/* set source block */
				if (lseek(fsource, source.blksize * source.getblkno, SEEK_SET) == (off_t)-1) return xerror("Seek error");
				source.onblk = read(fsource, bsource, source.blksize);
				source.curblkno = source.getblkno;
				goto process;
			case XD3_GOTHEADER:
			case XD3_WINSTART:
			case XD3_WINFINISH:
				/* no action necessary */
				goto process;
			default:
				/* error */
				return xerror("Error #3");
			}
		} while (!eof);

		free(bsource);
		free(bdelta);

		xd3_close_stream(&stream);
		xd3_free_stream(&stream);

		ok = 1;
	}

	if (fsource >= 0) close(fsource);
	if (fdelta >= 0) close(fdelta);
	if (fout >= 0) close(fout);

	if (!ok) unlink(filenameOut);

	return ok;
}
